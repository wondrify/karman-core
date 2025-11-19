package com.bertramlabs.plugins.karman.cifs

import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile

/**
 * SMB input stream for random access into a SmbFile
 * This allows starting at a specific offset within the smbfile
 */
class SmbRandomAccessFileInputStream extends InputStream {
	private final SmbRandomAccessFile file

	SmbRandomAccessFileInputStream(SmbFile smbFile, long offset) throws IOException {
		if (smbFile == null) {
			throw new IllegalArgumentException("smbFile cannot be null")
		}
		if (offset < 0) {
			throw new IllegalArgumentException("offset cannot be negative")
		}
		this.file = new SmbRandomAccessFile(smbFile, "r")
		try {
			this.file.seek(offset)
		} catch (IOException e) {
			this.file.close()
			throw e
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	int read() throws IOException {
		return file.read()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	int read(byte[] b) throws IOException {
		return file.read(b)
	}


	/**
	 * {@inheritDoc}
	 */
	@Override
	int read(byte[] b, int off, int len) throws IOException {
		return file.read(b, off, len)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	long skip(long n) throws IOException {
		long pos = file.getFilePointer()
		long len = file.length()
		long newPos = Math.min(pos + n, len)
		file.seek(newPos)
		return newPos - pos
	}

	@Override
	int available() throws IOException {
		long remaining = file.length() - file.getFilePointer()
		if (remaining <= 0) {
			return 0
		}
		return (int) Math.min(remaining, Integer.MAX_VALUE)
	}

	@Override
	void close() throws IOException {
		file.close()
	}

}

