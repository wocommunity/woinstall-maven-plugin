package org.wocommunity.maven.plugins.woinstall.archiver;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import org.wocommunity.maven.plugins.woinstall.io.BoundedInputStream;
import org.wocommunity.maven.plugins.woinstall.io.FileUtilities;
import org.wocommunity.maven.plugins.woinstall.ui.IWOInstallerProgressMonitor;
import org.wocommunity.maven.plugins.woinstall.ui.NullProgressMonitor;

public class CPIO {

	public static final int S_IFDIR = 16384;
	public static final int S_IFREG = 32768;
	public static final int S_IFLNK = 40960;

	private File _cpioFile;
	InputStream paxStream;
	long fileLength = 0;

	public CPIO(final File cpioFile) throws FileNotFoundException {
		this(new BufferedInputStream(new FileInputStream(cpioFile)));
		_cpioFile = cpioFile;
		fileLength = _cpioFile.length();

	}

	public CPIO(final InputStream input) {
		paxStream = input;
	}

	public void setLength(final long length) {
		fileLength = length;
	}

	@SuppressWarnings("unused")
	public void extractTo(final File destinationFolder,
			final IWOInstallerProgressMonitor progressMonitor) throws IOException {
		progressMonitor.beginTask("Extracting WebObjects ...", fileLength);

		long amount = 0;
		final List<Link> links = new LinkedList<>();

		try {
			final byte[] sixBuffer = new byte[6];
			final byte[] elevenBuffer = new byte[11];
			boolean done = false;
			do {
				final String magic = readString(paxStream, sixBuffer);
				if (!"070707".equals(magic)) {
					throw new IOException("Expected magic '070707' but got '" + magic + "' (next = "
							+ readString(paxStream, new byte[50]) + ").");
				}
				final String dev = readString(paxStream, sixBuffer);
				final String ino = readString(paxStream, sixBuffer);
				final String modeStr = readString(paxStream, sixBuffer);
				final String uid = readString(paxStream, sixBuffer);
				final String gid = readString(paxStream, sixBuffer);
				final String nlink = readString(paxStream, sixBuffer);
				final String rdev = readString(paxStream, sixBuffer);
				final String mtime = readString(paxStream, elevenBuffer);
				final String nameSizeStr = readString(paxStream, sixBuffer);
				final String fileSizeStr = readString(paxStream, elevenBuffer);

				final int nameSize = Integer.parseInt(nameSizeStr, 8);
				final String name = readString(paxStream, new byte[nameSize]);

				final int fileSize = Integer.parseInt(fileSizeStr, 8);

				if ("TRAILER!!!".equals(name)) {
					done = true;
				} else {
					final File destinationFile = toFile(destinationFolder, name);
					final int mode = Integer.parseInt(modeStr, 8);
					if ((mode & S_IFDIR) == S_IFDIR) {
						if (".".equals(name)) {
							// skip
						} else if (destinationFile.exists()) {
							throw new IOException("The directory '" + destinationFile + "' already exists.");
						} else if (!destinationFile.mkdirs()) {
							throw new IOException("Failed to create directory '" + destinationFile + "'.");
						}
						skipFully(paxStream, fileSize);
					} else if ((mode & S_IFLNK) == S_IFLNK) {
						final String realName = readString(paxStream, new byte[fileSize]);
						final File realFile = new File(realName);
						links.add(new Link(realFile, destinationFile));
					} else if ((mode & S_IFREG) == S_IFREG) {
						if (destinationFile.exists()) {
							throw new IOException("The file '" + destinationFile + "' already exists.");
						}
						final InputStream is = new BoundedInputStream(paxStream, 0, fileSize);
						final FileOutputStream fos = new FileOutputStream(destinationFile);
						FileUtilities.writeInputStreamToOutputStream(is, fos, fileSize, new NullProgressMonitor());
					} else {
						throw new IOException("Unknown mode " + modeStr + " for " + name + ".");
					}

					final int relativeAmount = 70 + nameSize + fileSize;
					amount += relativeAmount;
					progressMonitor.worked(amount);
				}

				if (progressMonitor.isCanceled()) {
					throw new IOException("Operation canceled.");
				}
			} while (!done);
		} finally {
//      System.out.println(amount + ":" + fileLength);
			paxStream.close();
		}
		progressMonitor.done();
		progressMonitor.beginTask("Linking WebObjects ...", links.size());
		Collections.sort(links, new LinkNameLengthComparator());
		int linkNum = 0;
		for (final Link link : links) {
			link.create();
			progressMonitor.worked(linkNum++);
		}
	}

	protected File toFile(final File workingDir, final String path) {
		String localPath = path.replaceFirst("^\\./", "");
		localPath = localPath.replace("/", File.separator);
		File file = new File(localPath);
		if (!file.isAbsolute()) {
			file = new File(workingDir, localPath);
		}
		return file;
	}

	protected String readString(final InputStream is, final byte[] b) throws IOException {
		readFully(is, b);
		int length;
		for (length = b.length - 1; length >= 0 && b[length] == 0; length--) {
			// skip
		}
		return new String(b, 0, length + 1);
	}

	protected byte[] readFully(final InputStream is, final byte[] b) throws IOException {
		return readFully(is, b, 0, b.length);
	}

	protected byte[] readFully(final InputStream is, final byte[] b, final int offset, final int length)
			throws IOException {
		int totalAmountRead = 0;
		while (totalAmountRead < length) {
			final int amountRead = is.read(b, offset + totalAmountRead, length - totalAmountRead);
			if (amountRead == -1) {
				throw new IOException("Stream ended before " + length + " bytes (read " + totalAmountRead + ")");
			}
			totalAmountRead += amountRead;
		}
		return b;
	}

	protected void skipFully(final InputStream inputStream, final long skip) throws IOException {
		long toSkip = skip;
		while (toSkip > 0) {
			toSkip -= inputStream.skip(toSkip);
		}
	}

	protected static class Link {
		private final File _realFile;
		private final File _linkFile;

		public Link(final File realFile, final File linkFile) {
			_realFile = realFile;
			_linkFile = linkFile;
		}

		public Link(final String realName, final String linkName) {
			_realFile = new File(realName);
			_linkFile = new File(linkName);
		}

		public File getRealFile() {
			return _realFile;
		}

		public File getLinkFile() {
			return _linkFile;
		}

		public void create() throws IOException {
			/*
			 * This was modified from original since Vista and up support symbolic links.
			 * Vista went end-of-life in 2017.
			 */
			Files.createSymbolicLink(_linkFile.getCanonicalFile().toPath(), _realFile.toPath());
		}

		protected void copyFileToFile(final File source, final File destination) throws IOException {
			if (!source.exists()) {
				throw new IOException(
						"The file '" + source + "' does not exist (tried to link to '" + destination + "').");
			}
			if (destination.exists()) {
				throw new IOException("The file '" + destination + "' already exists.");
			}
			if (source.isDirectory()) {
				if (!destination.mkdirs()) {
					throw new IOException("Failed to create the directory '" + destination + "'.");
				}
				for (final File child : source.listFiles()) {
					copyFileToFile(child, new File(destination, child.getName()));
				}
			} else {
				final FileInputStream fis = new FileInputStream(source);
				FileUtilities.writeInputStreamToFile(fis, destination, (int) source.length(),
						new NullProgressMonitor());
			}
		}
	}

	protected static class LinkNameLengthComparator implements Comparator<Link>, Serializable {

		@Override
		public int compare(final Link s1, final Link s2) {
			final int length1 = s1.getRealFile().toString().length();
			final int length2 = s2.getRealFile().toString().length();
			int comparison;
			if (length1 > length2) {
				comparison = 1;
			} else if (length1 < length2) {
				comparison = -1;
			} else {
				comparison = 0;
			}
			return comparison;
		}
	}
}
