package org.wocommunity.maven.plugins.woinstall.io;

public class BlockEntry implements Comparable<BlockEntry> {
	public Long offset;
	public Long length;

	public BlockEntry() {
	}

	public BlockEntry(final Long offset, final Long length) {
		this.offset = offset;
		this.length = length;
	}

	@Override
	public int compareTo(final BlockEntry o) {
		return offset.compareTo(o.offset);
	}

	@Override
	public String toString() {
		return "offset: " + offset + " length: " + length;
	}

}
