package net.xdob.vexra.io;

public class DigestCache {
	private final String fileName;
	private final long lastModified;
	private final long fileSize;
	private final Digest digest;

	public DigestCache(String fileName, long lastModified, long fileSize, Digest digest) {
		this.fileName = fileName;
		this.lastModified = lastModified;
		this.fileSize = fileSize;
		this.digest = digest;
	}

	public String getFileName() {
		return fileName;
	}

	public long getLastModified() {
		return lastModified;
	}

	public long getFileSize() {
		return fileSize;
	}

	public Digest getDigest() {
		return digest;
	}
}
