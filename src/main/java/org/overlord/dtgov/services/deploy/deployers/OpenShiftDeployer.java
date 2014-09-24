package org.overlord.dtgov.services.deploy.deployers;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.http.auth.AuthenticationException;

import org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactType;
import org.overlord.dtgov.common.targets.CustomTarget;
import org.overlord.sramp.atom.err.SrampAtomException;
import org.overlord.sramp.client.SrampAtomApiClient;
import org.overlord.sramp.client.SrampClientException;
import org.overlord.sramp.common.ArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.openshift.client.DeploymentTypes;
import com.openshift.client.IApplication;
import com.openshift.client.IDomain;
import com.openshift.client.IOpenShiftConnection;
import com.openshift.client.IUser;
import com.openshift.client.OpenShiftConnectionFactory;
import com.openshift.client.SSHKeyPair;
import com.openshift.client.SSHPublicKey;
import com.openshift.client.cartridge.query.LatestVersionOf;
import com.openshift.client.configuration.OpenShiftConfiguration;
import com.openshift.internal.client.ApplicationSSHSession;
import com.openshift.internal.client.utils.StreamUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 
 * Properties are read from ~/.openshift/express.conf or from system properties (e.g -Drhpassword=<pwd>).
 * OpenShift Domain & application name are read from DtgovDeploymentTarget stored in S-RAMP.
 * DTGov server needs to be launched with -Ddtgov.deployers.customDir pointing to dir with built deployer jar.
 * 
 * @author sbunciak
 *
 */
public class OpenShiftDeployer extends AbstractDeployer<CustomTarget> {

	private static Logger logger = LoggerFactory.getLogger(OpenShiftDeployer.class);

	private final String clientId = "dtgov-workflow";

	private final String SSH_PUBLIC_KEY = System.getProperty("user.home") + "/.ssh/dtgov_rsa.pub";
	private final String SSH_PRIVATE_KEY = System.getProperty("user.home") + "/.ssh/dtgov_rsa";
	// TODO: ssh_passphrase should not be in source code, most probably :-) 
	private final String SSH_PASSPHRASE = "overlord";

	/*
	 * (non-Javadoc)
	 * @see org.overlord.dtgov.services.deploy.Deployer#deploy(org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactType, org.overlord.dtgov.common.Target, org.overlord.sramp.client.SrampAtomApiClient)
	 */
	@Override
	public String deploy(BaseArtifactType artifact, CustomTarget target, SrampAtomApiClient client) throws Exception {
		// download jar to temp location
		final File applicationFile = downloadAppJarFromSramp(artifact, client);
		final String openshiftAppName = target.getProperty("application");
		final String openshiftDomain = target.getProperty("domain");
		// final String cartridge = target.getProperty("cartridge"); // WildFly, Tomcat, JBossAS
		/*
		 * Before OpenShift Deployer can manipulate resources on OpenShift, it has to connect to it. 
		 * It asks the OpenShiftConnectionFactory for a new connection.
		 */
		OpenShiftConfiguration openshiftConf = new OpenShiftConfiguration();
		IOpenShiftConnection connection = new OpenShiftConnectionFactory().getConnection(clientId,
				openshiftConf.getRhlogin(), openshiftConf.getPassword(), openshiftConf.getLibraServer());

		// Once you have your connection you can get your user instance which will allow you to create your the domain and the applications.
		IUser user = connection.getUser();

		logger.info("OpenShift Deployer logged under user: " + user.getRhlogin());
		/* 
		 * All resources on OpenShift are bound to a domain. We therefore have to make sure we have a domain in a first step, 
		 * we either get the existing one or create a new one.
		 */
		IDomain domain = user.getDefaultDomain();
		if (domain == null) {
			domain = user.createDomain(openshiftDomain);
		}

		logger.info("OpenShift Deployer using domain: " + openshiftDomain);

		/*
		 * In case we create a new domain, we have to make sure that OpenShift has our ssh-key. 
		 * The ssh-key is required as soon as you deal with an OpenShift git-repository, the application logs etc (not when manipulating resources). To keep things simple, we will stick to the default ssh-key id_rsa.pub and add it to OpenShift in case it's not present yet. When adding the key, we will use a unique name which is the current time in millisecons in this simplified example:
		 */
		SSHKeyPair.create(SSH_PASSPHRASE, SSH_PRIVATE_KEY, SSH_PUBLIC_KEY);
		// TODO: do not create a new ssh key pair if we can create an SSH connection

		user.getSSHKeyByName("dtgov-key").destroy();
		user.addSSHKey("dtgov-key", new SSHPublicKey(SSH_PUBLIC_KEY));

		/*
		 * Now that we have a domain, we are ready to create an application.
		 */
		IApplication application = domain.getApplicationByName(openshiftAppName);
		if (application == null) {
			//If there's no such application, it will create a new one:

			application = domain.createApplication(openshiftAppName, LatestVersionOf.jbossAs().get(user));
		}

		logger.info("OpenShift Deployer using application: " + application.getName());

		// Binary deploy the application
		application.setDeploymentType(DeploymentTypes.binary());
		Session session = createSSHSession(application.getSshUrl());
		ApplicationSSHSession applicationSession = new ApplicationSSHSession(application, session);

		// save application snapshot to (tar.gz archive) file
		File snapshotFile = File.createTempFile("openshift-snapshot", ".tar.gz");
		InputStream snapshot = applicationSession.saveDeploymentSnapshot();
		// snapshotFile.deleteOnExit();
		writeTo(snapshot, new FileOutputStream(snapshotFile));

		// append application jar file to the saved snapshot
		File modifiedSnapshotFile = appendAppJarInTarArchive(new FileInputStream(snapshotFile), applicationFile);

		// operations
		InputStream restoreOutput = applicationSession.restoreDeploymentSnapshot(new FileInputStream(
				modifiedSnapshotFile), true);
		StreamUtils.writeTo(restoreOutput, System.out);

		session.disconnect();
		return application.getApplicationUrl();
	}

	/*
	 * (non-Javadoc)
	 * @see org.overlord.dtgov.services.deploy.Deployer#undeploy(org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactType, org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactType, org.overlord.dtgov.common.Target, org.overlord.sramp.client.SrampAtomApiClient)
	 */
	@Override
	public void undeploy(BaseArtifactType prevVersionArtifact, BaseArtifactType undeployInfo, CustomTarget target,
			SrampAtomApiClient client)
			throws Exception {

		final File applicationFile = downloadAppJarFromSramp(prevVersionArtifact, client);
		final String openshiftAppName = target.getProperty("application");

		OpenShiftConfiguration openshiftConf = new OpenShiftConfiguration();
		IOpenShiftConnection connection = new OpenShiftConnectionFactory().getConnection(clientId,
				openshiftConf.getRhlogin(), openshiftConf.getPassword(),
				openshiftConf.getLibraServer());

		IUser user = connection.getUser();
		IDomain domain = user.getDefaultDomain();

		if (!domain.equals(target.getProperty("domain"))) {
			logger.warn("OpenShift deployer is using different domain for undeployment than configured!");
		}

		IApplication application = domain.getApplicationByName(openshiftAppName);

		SSHKeyPair.create(SSH_PASSPHRASE, SSH_PRIVATE_KEY, SSH_PUBLIC_KEY);
		// TODO: do not create a new ssh key pair if we can create an SSH connection

		user.getSSHKeyByName("dtgov-key").destroy();
		user.addSSHKey("dtgov-key", new SSHPublicKey(SSH_PUBLIC_KEY));

		// Binary deploy the application
		application.setDeploymentType(DeploymentTypes.binary());
		Session session = createSSHSession(application.getSshUrl());
		ApplicationSSHSession applicationSession = new ApplicationSSHSession(application, session);

		// save application snapshot to (tar.gz archive) file
		File snapshotFile = File.createTempFile("openshift-snapshot", ".tar.gz");
		InputStream snapshot = applicationSession.saveDeploymentSnapshot();
		// snapshotFile.deleteOnExit();
		writeTo(snapshot, new FileOutputStream(snapshotFile));

		// append application jar file to the saved snapshot
		File modifiedSnapshotFile = removeAppJarFromTarArchive(new FileInputStream(snapshotFile), applicationFile);

		// operations
		InputStream restoreOutput = applicationSession.restoreDeploymentSnapshot(new FileInputStream(
				modifiedSnapshotFile), true);
		StreamUtils.writeTo(restoreOutput, System.out);

		session.disconnect();
	}

	private Session createSSHSession(String sshUrl) throws JSchException, URISyntaxException {
		JSch.setConfig("StrictHostKeyChecking", "no");
		JSch jsch = new JSch();
		jsch.addIdentity(SSH_PRIVATE_KEY, SSH_PASSPHRASE);
		URI sshUri = new URI(sshUrl);
		Session session = jsch.getSession(sshUri.getUserInfo(), sshUri.getHost());
		session.connect();
		return session;
	}

	private void writeTo(InputStream inputStream, FileOutputStream fileOut) throws IOException {
		try {
			StreamUtils.writeTo(inputStream, fileOut);
		} finally {
			StreamUtils.close(inputStream);
			fileOut.flush();
			StreamUtils.close(fileOut);
		}
	}

	private File appendAppJarInTarArchive(InputStream tarIn, File appJar) throws AuthenticationException, IOException {

		File newArchive = File.createTempFile("new-openshift-snapshot", ".tar.gz");
		// newArchive.deleteOnExit();
		TarArchiveOutputStream newArchiveOut = new TarArchiveOutputStream(new GZIPOutputStream(new FileOutputStream(
				newArchive)));
		newArchiveOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
		TarArchiveInputStream archiveIn = new TarArchiveInputStream(new GZIPInputStream(tarIn));

		try {
			// copy the existing entries
			for (ArchiveEntry nextEntry = null; (nextEntry = archiveIn.getNextEntry()) != null;) {
				newArchiveOut.putArchiveEntry(nextEntry);
				IOUtils.copy(archiveIn, newArchiveOut);
				newArchiveOut.closeArchiveEntry();
			}

			TarArchiveEntry newEntry = new TarArchiveEntry(appJar, "/repo/deployments/" + appJar.getName());
			newEntry.setSize(appJar.length());
			newArchiveOut.putArchiveEntry(newEntry);
			IOUtils.copy(new FileInputStream(appJar), newArchiveOut);
			newArchiveOut.closeArchiveEntry();
			return newArchive;
		} finally {
			newArchiveOut.finish();
			newArchiveOut.flush();
			StreamUtils.close(archiveIn);
			StreamUtils.close(newArchiveOut);
		}
	}

	private File removeAppJarFromTarArchive(InputStream tarIn, File appJar) throws AuthenticationException, IOException {
		File newArchive = File.createTempFile("new-openshift-snapshot", ".tar.gz");
		// newArchive.deleteOnExit();
		TarArchiveOutputStream newArchiveOut = new TarArchiveOutputStream(new GZIPOutputStream(new FileOutputStream(
				newArchive)));
		newArchiveOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
		TarArchiveInputStream archiveIn = new TarArchiveInputStream(new GZIPInputStream(tarIn));

		try {
			// copy the existing entries
			for (ArchiveEntry nextEntry = null; (nextEntry = archiveIn.getNextEntry()) != null;) {
				if (!nextEntry.getName().contains(appJar.getName())) {
					newArchiveOut.putArchiveEntry(nextEntry);
					IOUtils.copy(archiveIn, newArchiveOut);
					newArchiveOut.closeArchiveEntry();
				}
			}

			return newArchive;
		} finally {
			newArchiveOut.finish();
			newArchiveOut.flush();
			StreamUtils.close(archiveIn);
			StreamUtils.close(newArchiveOut);
		}
	}

	private File downloadAppJarFromSramp(BaseArtifactType artifact, SrampAtomApiClient client)
			throws SrampClientException,
			SrampAtomException, IOException {
		final InputStream is = client.getArtifactContent(ArtifactType.valueOf(artifact), artifact.getUuid());
		final File file = new File(System.getProperty("java.io.tmpdir") + "/" + artifact.getName());
		if (file.exists()) {
			file.delete();
		}

		file.createNewFile();
		final OutputStream os = new FileOutputStream(file);
		IOUtils.copy(is, os);
		return file;
	}	
	// TODO: add support for multiple cartridges (Tomcat, WildFly, ...)
}
