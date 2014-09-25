package org.overlord.dtgov.services.utils;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.http.auth.AuthenticationException;

import org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactType;
import org.overlord.sramp.atom.err.SrampAtomException;
import org.overlord.sramp.client.SrampAtomApiClient;
import org.overlord.sramp.client.SrampClientException;
import org.overlord.sramp.common.ArtifactType;

import com.openshift.internal.client.utils.StreamUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 
 * @author sbunciak
 *
 */
public class SrampTarUtils {

	/**
	 * Download JAR from S-RAMP and save it to tmp.
	 * 
	 * @param artifact
	 * @param client
	 * @return Downloaded File
	 * @throws SrampClientException
	 * @throws SrampAtomException
	 * @throws IOException
	 */
	public static File downloadAppJarFromSramp(BaseArtifactType artifact, SrampAtomApiClient client)
			throws SrampClientException, SrampAtomException, IOException {

		final InputStream is = client.getArtifactContent(ArtifactType.valueOf(artifact), artifact.getUuid());
		final File file = new File(System.getProperty("java.io.tmpdir") + "/" + artifact.getName());
		if (file.exists()) {
			file.delete();
		}

		file.createNewFile();
		IOUtils.copy(is, new FileOutputStream(file));
		return file;
	}

	/**
	 * Removes JAR file from TAR Archive.
	 * 
	 * @param tarIn
	 * @param appJar
	 * @return Resulting TAR Archive file.
	 * @throws AuthenticationException
	 * @throws IOException
	 */
	public static File removeAppJarFromTarArchive(InputStream tarIn, File appJar) throws AuthenticationException,
			IOException {
		final File newArchive = File.createTempFile("new-openshift-snapshot", ".tar.gz");
		// newArchive.deleteOnExit();
		final TarArchiveOutputStream newArchiveOut = new TarArchiveOutputStream(new GZIPOutputStream(
				new FileOutputStream(
						newArchive)));
		newArchiveOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
		final TarArchiveInputStream archiveIn = new TarArchiveInputStream(new GZIPInputStream(tarIn));

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

	/**
	 * Appends given JAR file into TAR Archive under /repo/&#60;deploymentsDir&#62;
	 * 
	 * @param tarIn
	 * @param appJar
	 * @param deploymentsDir
	 * @return
	 * @throws AuthenticationException
	 * @throws IOException
	 */
	public static File appendAppJarInTarArchive(InputStream tarIn, File appJar, String deploymentsDir)
			throws AuthenticationException, IOException {

		final File newArchive = File.createTempFile("new-openshift-snapshot", ".tar.gz");
		// newArchive.deleteOnExit();
		final TarArchiveOutputStream newArchiveOut = new TarArchiveOutputStream(new GZIPOutputStream(
				new FileOutputStream(newArchive)));
		newArchiveOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
		final TarArchiveInputStream archiveIn = new TarArchiveInputStream(new GZIPInputStream(tarIn));

		try {
			// copy the existing entries
			for (ArchiveEntry nextEntry = null; (nextEntry = archiveIn.getNextEntry()) != null;) {
				newArchiveOut.putArchiveEntry(nextEntry);
				IOUtils.copy(archiveIn, newArchiveOut);
				newArchiveOut.closeArchiveEntry();
			}

			TarArchiveEntry newEntry = new TarArchiveEntry(appJar, "/repo/" + deploymentsDir + "/" + appJar.getName());
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

	/**
	 * @see {@link SrampTarUtils#appendAppJarInTarArchive(InputStream, File)}
	 * @param tarIn
	 * @param appJar
	 * @return
	 * @throws AuthenticationException
	 * @throws IOException
	 */
	public static File appendAppJarInTarArchive(InputStream tarIn, File appJar) throws AuthenticationException,
			IOException {
		return appendAppJarInTarArchive(tarIn, appJar, "deployments");
	}
}
