package org.wocommunity.maven.plugins.woinstall.archiver;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.wocommunity.maven.plugins.woinstall.bzip2.CBZip2InputStream;
import org.wocommunity.maven.plugins.woinstall.io.BoundedInputStream;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class XarFile {
	private static final long XAR_HEADER_MAGIC = 0x78617221;
	private static final int XAR_HEADER_SIZE = 28;
	private static final String[] XAR_CKSUM = { "NONE", "SHA1", "MD5" };
	private static final int BYTE_MASK = 0xff;

	private final byte[] byte2 = new byte[2];
	private final byte[] byte4 = new byte[4];
	private final byte[] byte8 = new byte[8];
	private final Map<String, XarEntry> entries = new HashMap<>();

	private File file;

	private XarHeader header;
	private XarToc toc;
	private InputStream inputStream;
	private InputStream lastInputStream;
	private long currentOffset = 0;

	private class XarHeader {
		private static final int SHORT_MASK = 0xffff;

		public long magic;
		public int size;
		public int version;
		public BigInteger tocLengthCompressed;
		public BigInteger tocLengthUncompressed;
		public long checksumAlgorithm;

		protected XarHeader() throws IOException {
			magic = readUint32();
			size = readUint16();
			version = readUint16();
			tocLengthCompressed = readUint64();
			tocLengthUncompressed = readUint64();
			checksumAlgorithm = readUint32();
		}

		@SuppressWarnings("unused")
		public void dumpHeader() {
			System.out.println("\nmagic:\t\t\t 0x" + Long.toHexString(magic >> Short.SIZE & SHORT_MASK) +
					Long.toHexString(magic & SHORT_MASK)
					+ " " + (magic == XAR_HEADER_MAGIC ? "(OK)" : "(BAD)"));
			System.out.println("size:\t\t\t " + size);
			System.out.println("version:\t\t " + version);
			System.out.println("Compressed TOC length:\t " + tocLengthCompressed);
			System.out.println("Uncompressed TOC length: " + tocLengthUncompressed);
			System.out.println("Checksum algorithm:\t " + checksumAlgorithm + " (" + getCksumName() + ")");
		}
	}

	private class XarToc {
		private static final int BUFFER_SIZE = 255;
		private final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		private DocumentBuilder builder;

		private Document doc;
		private String data;

		protected XarToc() throws IOException {
			try {
				data = readToc();
				builder = factory.newDocumentBuilder();
				doc = builder.parse(new InputSource(new StringReader(data)));
			} catch (final ParserConfigurationException | SAXException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		private String readToc() throws IOException {
			try (InputStream inflate = new InflaterInputStream(new BoundedInputStream(inputStream, 0,
					header.tocLengthCompressed.longValue()))) {
				final byte[] buffer = new byte[BUFFER_SIZE];
				BigInteger length = new BigInteger(header.tocLengthUncompressed.toByteArray());
				int read;
				final StringBuilder tocFile = new StringBuilder();
				while ((read = inflate.read(buffer, 0,
						length.intValue() > BUFFER_SIZE ? BUFFER_SIZE : length.intValue())) > 0) {
					tocFile.append(new String(buffer, 0, read));
					length = length.subtract(BigInteger.valueOf(read));
				}
				return tocFile.toString();

			}
		}

		@Override
		public String toString() {
			return data;
		}

		public Map<String, XarEntry> getEntries() {
			return XarEntry.getEntries(doc);
		}
	}

	public class XarInputStream extends InputStream {
		private final InputStream _delegate;
		private final XarEntry _entry;
		private final MessageDigest _digest;

		public XarInputStream(final XarEntry entry, final InputStream input) {
			_entry = entry;
			_delegate = input;
			if (_entry.hasChecksum()) {
				_digest = _entry.getMessageDigest(getCksumName());
				_digest.reset();
			} else {
				_digest = null;
			}
		}

		@Override
		public int read() throws IOException {
			final int result = _delegate.read();
			if (result == -1) {
				if (!validChecksum()) {
					throw new XarException("invalid checksum");
				}
				return result;
			}
			if (_digest != null) {
				_digest.update((byte) (result & BYTE_MASK));
			}
			return result;
		}

		@Override
		public int read(final byte[] buffer, final int off, final int len) throws IOException {
			final int result = _delegate.read(buffer, off, len);
			if (result == -1) {
				if (!validChecksum()) {
					throw new XarException("invalid checksum");
				}
				return result;
			}
			if (_digest != null) {
				_digest.update(buffer, off, result);
			}
			return result;
		}

		private boolean validChecksum() {
			if (_digest != null) {
				final String checksum = toChecksum(_digest.digest());
				if (_entry.hasChecksum() && !checksum.equals(_entry.getExtractedChecksum())) {
					return false;
				}
			}
			return true;
		}

		private String toChecksum(final byte[] data) {
			final StringBuilder checksum = new StringBuilder();
			for (final byte element : data) {
				final String hexString = Integer.toHexString(element & BYTE_MASK);
				if (hexString.length() == 1) {
					checksum.append("0");
				}
				checksum.append(hexString);
			}
			return checksum.toString();
		}
	}

//	public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
//	}

	public XarFile(final String name) throws IOException {
		this(new File(name));
	}

	public XarFile(final File file) throws IOException {
		if (!file.exists() || file.length() < XAR_HEADER_SIZE) {
			throw new IOException("error reading header");
		}
		this.file = file;
		setInputStream(new BufferedInputStream(new FileInputStream(file)));
	}

	public XarFile(final InputStream stream) throws IOException {
		setInputStream(stream);
	}

	private void setInputStream(final InputStream stream) throws IOException {
		inputStream = stream;
		header = new XarHeader();
		if (header.magic != XAR_HEADER_MAGIC) {
			throw new XarException("invalid magic header");
		}
		getToc();
		try {
			run();
		} catch (final Exception e) {
			// TODO: handle exception
		}
		entries.putAll(getToc().getEntries());
	}

	private XarToc getToc() {
		if (toc == null) {
			try {
				toc = new XarToc();
			} catch (final IOException e) {
				e.printStackTrace();
			}
		}
		return toc;
	}

	public void run() throws IOException, NoSuchAlgorithmException {
//		System.out.println(toc);
//		XarEntry entry = getEntry("PackageInfo");
//		InputStream in = getInputStream(entry);
//		Writer writer = new StringWriter();
//		int i;
//		while ((i = in.read()) >= 0) {
//			writer.write(i);
//		}
//		System.out.print(writer.toString());
	}

	public XarEntry getEntry(final String name) {
		if (name == null) {
			throw new IllegalArgumentException("name");
		}
		return entries.get(name);
	}

	public Map<String, XarEntry> getEntries() {
		return getToc().getEntries();
	}

	public InputStream getInputStream(final String name) throws IOException {
		return getInputStream(getEntry(name));
	}

	public InputStream getInputStream(final XarEntry entry) throws IOException {
		if (entry == null) {
			throw new IllegalArgumentException("entry");
		}
		synchronized (this) {
			try {
				final String compression = entry.getCompression();
				if (lastInputStream != null) {
					while (lastInputStream.read() != -1) {
						/* read to end of stream */ }
				}
				if (entry.getOffset() <= currentOffset) {
					if (file == null) {
						throw new XarException("Cannot seek backwards through stream");
					}
					lastInputStream.close();
					inputStream = new BufferedInputStream(new FileInputStream(file));
					final long toSkip = header.size + header.tocLengthCompressed.longValue();
					skipFully(inputStream, toSkip);
					currentOffset = 0;
				}
				long newOffset = entry.getOffset() - currentOffset;
				currentOffset = entry.getOffset() + entry.getLength();
				InputStream input = new BoundedInputStream(inputStream, newOffset, entry.getLength());
				if (compression == null) {
					// Do nothing
				} else if ("bzip2".equals(compression)) {
					skipFully(input, 2);
					input = new CBZip2InputStream(input);
				} else if ("gzip".equals(compression)) {
					input = new GZIPInputStream(input);
				}
				lastInputStream = new XarInputStream(entry, input);
				return lastInputStream;
			} catch (final FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			throw new XarException("something unexpected happened");
		}
	}

	private static void skipFully(final InputStream inputStream, final long skip) throws IOException {
		long toSkip = skip;
		while (toSkip > 0) {
			toSkip -= inputStream.skip(toSkip);
		}
	}

	private static void readFully(final InputStream inputStream, final byte[] buffer) throws IOException {
		int read = 0;
		while (read < buffer.length) {
			read += inputStream.read(buffer, read, buffer.length - read);
		}
	}

	private String getCksumName() {
		if (header.checksumAlgorithm < 0 || header.checksumAlgorithm > XAR_CKSUM.length - 1) {
			return "unknown";
		}
		return XAR_CKSUM[(int) header.checksumAlgorithm];
	}

	private int readUint16() throws IOException {
		readFully(inputStream, byte2);
		return (byte2[0] & BYTE_MASK) << Byte.SIZE | byte2[1] & BYTE_MASK;
	}

	private long readUint32() throws IOException {
		readFully(inputStream, byte4);
		long result = 0;
		for (int i = 0; i < byte4.length; i++) {
			result |= (byte4[i] & BYTE_MASK) << (byte4.length - (i + 1)) * Byte.SIZE;
		}
		return result;
	}

	private byte[] readByte8() throws IOException {
		readFully(inputStream, byte8);
		return byte8.clone();
	}

	private BigInteger readUint64() throws IOException {
		return new BigInteger(readByte8());
	}
}
