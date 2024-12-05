package org.wocommunity.maven.plugins.woinstall;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wocommunity.maven.plugins.woinstall.archiver.CPIO;
import org.wocommunity.maven.plugins.woinstall.archiver.XarFile;
import org.wocommunity.maven.plugins.woinstall.io.BlockEntry;
import org.wocommunity.maven.plugins.woinstall.io.MultiBlockInputStream;
import org.wocommunity.maven.plugins.woinstall.ui.IWOInstallerProgressMonitor;
import org.wocommunity.maven.plugins.woinstall.ui.NullProgressMonitor;

public enum WebObjectsInstaller {
	WO_5_4_3("5.4.3",
			"https://download.info.apple.com/Mac_OS_X/061-4634.20080915.3ijd0/WebObjects543.dmg",
			"3d671b7513b12aa06dde2b14acb746c9a0a505bc8bc47340337d6ae127dfb0d8",
			153786259L,
			166167249L,
			58556928L,
			107601091L) {
		@Override
		protected InputStream getInstallFileInputStream(final File rootDir,
				final IWOInstallerProgressMonitor progressMonitor)
				throws IOException {
			return new GZIPInputStream(
					new XarFile(
							new MultiBlockInputStream(
									new BufferedInputStream(
											new FileInputStream(getInstallerFile(rootDir))),
									Arrays.asList(new BlockEntry(getEntryOffset(), getEntryLength()))))
							.getInputStream("Payload"));
		}
	},
	WO_5_3_3("5.3.3",
			"https://download.info.apple.com/Mac_OS_X/061-2998.20070215.33woU/WebObjects5.3.3Update.dmg",
			"bedc14cbcb82a2a64415f2f322a9ccc6bb400aab72895c5fe9f086d085bc8698",
			51252394L,
			42321716L,
			11608064L,
			29672581L) {
		@Override
		protected InputStream getInstallFileInputStream(final File rootDir,
				final IWOInstallerProgressMonitor progressMonitor)
				throws IOException {
			return new GZIPInputStream(
					new MultiBlockInputStream(
							new BufferedInputStream(
									new FileInputStream(getInstallerFile(rootDir))),
							Arrays.asList(new BlockEntry(getEntryOffset(), getEntryLength()))));
		}
	};

	private static final Logger LOG = LoggerFactory.getLogger(WebObjectsInstaller.class);

	private static final Map<String, List<String>> DEPENDENCY_MAP = initDependencyMap();

	WebObjectsInstaller(final String version,
			final String url,
			final String checksum,
			final Long rawLength,
			final Long fileLength,
			final Long entryOffset,
			final Long entryLength) {
		this.version = version;
		this.url = url;
		this.checksum = checksum;
		this.rawLength = rawLength;
		this.fileLength = fileLength;
		this.entryOffset = entryOffset;
		this.entryLength = entryLength;
	}

	private final String version;
	private final String url;
	private final String checksum;
	private final Long rawLength;
	private final Long fileLength;
	private final Long entryOffset;
	private final Long entryLength;

	protected abstract InputStream getInstallFileInputStream(File rootDir, IWOInstallerProgressMonitor progressMonitor)
			throws IOException;

	private static Map<String, List<String>> initDependencyMap() {
		final Map<String, List<String>> map = new ConcurrentHashMap<>();
		map.put("JavaEOControl", Collections.unmodifiableList(Arrays.asList("JavaFoundation")));
		map.put("JavaWebObjects", Collections.unmodifiableList(Arrays.asList("JavaXML", "JavaEOControl")));
		map.put("JavaWOExtensions", Collections.unmodifiableList(Arrays.asList("JavaWebObjects")));
		map.put("JavaEOAccess", Collections.unmodifiableList(Arrays.asList("JavaFoundation", "JavaEOControl")));
		map.put("JavaDTWGeneration", Collections.unmodifiableList(Arrays.asList("JavaWebObjects")));
		map.put("JavaDirectToWeb", Collections.unmodifiableList(Arrays.asList("JavaEOProject", "JavaDTWGeneration")));
		map.put("JavaEOProject", Collections.unmodifiableList(Arrays.asList("JavaWebObjects", "JavaEOAccess")));
		map.put("JavaJDBCAdaptor", Collections.unmodifiableList(Arrays.asList("JavaEOAccess")));
		return Collections.unmodifiableMap(map);
	}

	public Map<String, List<String>> dependencyMap() {
		/*
		 * In theory, different version of WO could have different dependency graphs. In
		 * reality, 5.3 and 5.4 are the same. If there were ever a 5.5 to come along and
		 * change that, this method would become abstract and each enum would return its
		 * own dependencyMap.
		 */
		return DEPENDENCY_MAP;
	}

	public static WebObjectsInstaller forVersion(final String version)
			throws UnknownVersionException {
		for (final WebObjectsInstaller installer : values()) {
			if (installer.version.equals(version)) {
				return installer;
			}
		}
		throw new UnknownVersionException(version);
	}

	public String getVersion() {
		return version;
	}

	public String getUrl() {
		return url;
	}

	public String getChecksum() {
		return checksum;
	}

	public Long getRawLength() {
		return rawLength;
	}

	public Long getFileLength() {
		return fileLength;
	}

	public Long getEntryOffset() {
		return entryOffset;
	}

	public Long getEntryLength() {
		return entryLength;
	}

	/**
	 * @return the path to the webobjects installer file relative to the repository
	 *         root.
	 */
	public String getInstallerFilePath() {
		return "com/webobjects/.archive/WebObjects_" + version + ".dmg";
	}

	/**
	 * Given a rootDir, produce a file for the install file location.
	 *
	 * @param rootDir the root repository directory.
	 * @return the installer file location.
	 */
	public File getInstallerFile(final File rootDir) {
		return new File(rootDir, getInstallerFilePath());
	}

	/**
	 * @return the path to the webobjects installer download file relative to the
	 *         repository root.
	 */
	public String getInstallerDownloadFilePath() {
		return getInstallerFilePath() + ".download";
	}

	/**
	 * Given a rootDir, produce a file for the install download file location. Once
	 * the download is completed, this file will be moved to the install file
	 * location.
	 *
	 * @param rootDir the root repository directory.
	 * @return the installer download file location.
	 */
	public File getInstallerDownloadFile(final File rootDir) {
		return new File(rootDir, getInstallerDownloadFilePath());
	}

	public void installNextRoot(final File rootDir) throws IOException {
		final File nextRoot = getNextRoot(rootDir);
		if (nextRoot.exists()) {
			LOG.debug("next root exists. skipping installation step.");
			return;
		}
		nextRoot.mkdirs();
		final IWOInstallerProgressMonitor progressMonitor = new NullProgressMonitor();
		try (InputStream in = getInstallFileInputStream(rootDir, progressMonitor)) {
			final CPIO cpio = new CPIO(in);
			cpio.setLength(getRawLength());
			cpio.extractTo(nextRoot, progressMonitor);
			progressMonitor.done();
		}
	}

	public File getNextRoot(final File rootDir) {
		return new File(rootDir, "com/webobjects/.next_roots/" + getVersion() + "/");
	}

	public File getJarRoot(final File rootDir) {
		return new File(getNextRoot(rootDir), "Library/WebObjects/lib/");
	}
}
