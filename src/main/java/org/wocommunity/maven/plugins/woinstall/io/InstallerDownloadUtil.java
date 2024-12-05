package org.wocommunity.maven.plugins.woinstall.io;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wocommunity.maven.plugins.woinstall.WebObjectsInstaller;

public class InstallerDownloadUtil {
	private static final Logger LOG = LoggerFactory.getLogger(InstallerDownloadUtil.class);

	public static void downloadInstallerToRepo(final WebObjectsInstaller installer, final File localRepo)
			throws IOException {
		final File installFile = installer.getInstallerFile(localRepo);
		if (installFile.exists()) {
			LOG.debug("Installer found: {}", installFile.getPath());
		} else {
			final File downloadFile = installer.getInstallerDownloadFile(localRepo);
			if (downloadFile.exists()) {
				LOG.info("Resuming download at {}", downloadFile.getPath());
			} else {
				LOG.info("Starting download at {}", downloadFile.getPath());
			}
			// download file to downloadFile;
			downloadToFile(installer.getUrl(), downloadFile);

			// check install file checksum & move file to installFile location
			MessageDigest md;
			try {
				md = MessageDigest.getInstance("SHA-256");
			} catch (final NoSuchAlgorithmException e) {
				throw new RuntimeException("This should never happen", e);
			}
			try (InputStream in = new DigestInputStream(new FileInputStream(downloadFile), md)) {
				Files.copy(in, installFile.toPath());
				downloadFile.delete();
				final String checksum = hexString(md);
				if (!installer.getChecksum().equals(checksum)) {
					// Corrupt file. Delete and throw exception
					installFile.delete();
					throw new IOException("Downloaded file checksum " + checksum
							+ " does not match expected checksum of " + installer.getChecksum());
				}
			}
		}
	}

	static String hexString(final MessageDigest md) {
		final StringBuilder hex = new StringBuilder(md.getDigestLength());
		for (final byte b : md.digest()) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	static void downloadToFile(final String url, final File downloadFile)
			throws IOException {
		downloadToFile(new URL(url), downloadFile);
	}

	static void downloadToFile(final URL url, final File downloadFile)
			throws IOException {
		final boolean exists = downloadFile.exists();
		if (!exists) {
			downloadFile.getParentFile().mkdirs();
			downloadFile.createNewFile();
		}
		try (BufferedInputStream in = new BufferedInputStream(urlToInputStream(url, downloadFile));
				FileOutputStream out = new FileOutputStream(downloadFile, exists);) {
			final int size = 1024;
			final byte[] buff = new byte[size];
			int read;
			while ((read = in.read(buff, 0, size)) != -1) {
				out.write(buff, 0, read);
			}
		}
	}

	static InputStream urlToInputStream(final URL url, final File downloadFile)
			throws IOException {
		final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(15000);
		conn.setReadTimeout(15000);
		conn.setRequestMethod("HEAD");
		conn.connect();
		final int responseCode = conn.getResponseCode();
		if (responseCode >= 300 && responseCode < 400) {
			final String location = conn.getHeaderField("Location");
			URL redirectUrl;
			try {
				redirectUrl = new URL(location);
			} catch (final MalformedURLException e) {
				redirectUrl = new URL(url.getProtocol() + "://" + url.getHost() + location);
			}
			LOG.debug("Redirecting to url {}", redirectUrl);
			return urlToInputStream(redirectUrl, downloadFile);
		}
		if (responseCode >= 200 && responseCode < 300) {
			final long fileSize = conn.getContentLengthLong();
			final long existingSize = downloadFile.length();
			if (fileSize <= existingSize) {
				// Download done?
				return new ByteArrayInputStream(new byte[0]);
			}
			final HttpURLConnection conn2 = (HttpURLConnection) url.openConnection();
			if (existingSize > 0L) {
				conn2.setRequestProperty("Range", "bytes=" + existingSize + "-" + fileSize);
			}
			LOG.info("Downloading {} bytes, please wait.", fileSize - existingSize);
			conn2.connect();
			return conn2.getInputStream();
		}
		LOG.error("Unexepcted response code {} for url {}", responseCode, url);
		throw new RuntimeException("Unexepcted response from url");
	}
}
