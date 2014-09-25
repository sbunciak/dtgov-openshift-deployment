package org.overlord.dtgov.services.deploy.deployers;

import org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactType;
import org.overlord.dtgov.common.targets.CustomTarget;
import org.overlord.dtgov.services.utils.SrampTarUtils;
import org.overlord.sramp.client.SrampAtomApiClient;
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
import com.openshift.client.OpenShiftException;
import com.openshift.client.SSHKeyPair;
import com.openshift.client.SSHPublicKey;
import com.openshift.client.cartridge.query.LatestVersionOf;
import com.openshift.client.configuration.OpenShiftConfiguration;
import com.openshift.internal.client.ApplicationSSHSession;
import com.openshift.internal.client.utils.StreamUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 
 * Properties are read from ~/.openshift/express.conf or from system properties (e.g -Drhpassword=<pwd>).
 * OpenShift Domain & application name are read from DtgovDeploymentTarget stored in S-RAMP.
 * DTGov server needs to be launched with -Ddtgov.deployers.customDir pointing to dir with built deployer jar.
 * TODO: add support for multiple cartridges (Tomcat, WildFly, ...)
 * @author sbunciak
 *
 */
public class OpenShiftDeployer extends AbstractDeployer<CustomTarget> {

	private static Logger logger = LoggerFactory.getLogger(OpenShiftDeployer.class);

	private final String clientId = "dtgov-workflow";

	private final String SSH_PUBLIC_KEY = System.getProperty("user.home") + "/.ssh/dtgov_rsa.pub";
	private final String SSH_PRIVATE_KEY = System.getProperty("user.home") + "/.ssh/dtgov_rsa";
	private final String SSH_PASSPHRASE = System.getProperty("ssh_passphrase", "overlord");

	/*
	 * (non-Javadoc)
	 * @see org.overlord.dtgov.services.deploy.Deployer#deploy(org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactType, org.overlord.dtgov.common.Target, org.overlord.sramp.client.SrampAtomApiClient)
	 */
	@Override
	public String deploy(BaseArtifactType artifact, CustomTarget target, SrampAtomApiClient client) throws Exception {
		// download jar to temp location
		final File applicationFile = SrampTarUtils.downloadAppJarFromSramp(artifact, client);
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
		setUpSSHKeys(user);

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
		File modifiedSnapshotFile = SrampTarUtils.appendAppJarInTarArchive(new FileInputStream(snapshotFile),
				applicationFile);

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

		final File applicationFile = SrampTarUtils.downloadAppJarFromSramp(prevVersionArtifact, client);
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
		
		setUpSSHKeys(user);

		IApplication application = domain.getApplicationByName(openshiftAppName);

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
		File modifiedSnapshotFile = SrampTarUtils.removeAppJarFromTarArchive(new FileInputStream(snapshotFile),
				applicationFile);

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
	
	private void setUpSSHKeys(IUser user) throws OpenShiftException, FileNotFoundException, IOException {
		boolean createNew = false;
		
		try {
			SSHKeyPair keyPair = SSHKeyPair.load(SSH_PRIVATE_KEY, SSH_PUBLIC_KEY);
			if (!user.hasSSHPublicKey(keyPair.getPublicKey())) {
				// keys does not match - create new pair
				createNew = true;
			}
		} catch (OpenShiftException e) {
			// exception occured - create new pair
			createNew = true;
		}
		
		if (createNew) {
			// Create new pair if the old on does not exist
			SSHKeyPair.create(SSH_PASSPHRASE, SSH_PRIVATE_KEY, SSH_PUBLIC_KEY);
			user.getSSHKeyByName("dtgov-key").destroy();
			user.addSSHKey("dtgov-key", new SSHPublicKey(SSH_PUBLIC_KEY));
		}
	}
}
