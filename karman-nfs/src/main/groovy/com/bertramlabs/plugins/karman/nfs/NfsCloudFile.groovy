package com.bertramlabs.plugins.karman.nfs

import com.bertramlabs.plugins.karman.CloudFile
import com.bertramlabs.plugins.karman.Directory
import com.bertramlabs.plugins.karman.util.Mimetypes
import com.emc.ecs.nfsclient.nfs.NfsWriteRequest
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream
import groovy.transform.CompileStatic
import groovy.util.logging.Commons

/**
 * Wrapped implementation of a CloudFile for Karman leveraging the NFSv3 interfaces
 *
 * @author David Estes
 */
@Commons
class NfsCloudFile extends CloudFile{

	NfsStorageProvider provider
	Nfs3File baseFile
	NfsDirectory parent
	InputStream sourceStream = null

	URL getURL(Date expirationDate = null) {
		new URL("${provider.baseUrl}/${parent.name}/${name}")
	}

	@Override
	InputStream getInputStream() {
		if(baseFile.exists()) {
			return new NfsFileInputStream(baseFile);
		}
		return null;
	}

	@Override
	void setInputStream(InputStream is) {
		sourceStream = is
	}

	@Override
	OutputStream getOutputStream() {
		if(!baseFile.exists()) {
			baseFile.createNewFile()
		}
		return new NfsFileOutputStream(baseFile, NfsWriteRequest.UNSTABLE)
	}

	@Override
	String getText(String encoding=null) {
		getInputStream()?.text
	}

	@Override
	byte[] getBytes() {
		getInputStream()?.bytes
	}

	@Override
	void setText(String text) {
		sourceStream = new ByteArrayInputStream(text.bytes)
	}

	@Override
	void setBytes(Object bytes) {
		sourceStream = new ByteArrayInputStream(bytes)
	}

	@Override
	Long getContentLength() {
		baseFile.length()
	}

	Date getDateModified() {
		return new Date(baseFile.lastModified())
	}

	Boolean isDirectory() {
		if(!baseFile.exists()) {
			return false
		}
		return baseFile.isDirectory()
	}

	Boolean isFile() {
		if(!baseFile.exists()) {
			return false
		}
		return baseFile.isFile()
	}

	@Override
	String getContentType() {
		return Mimetypes.instance.getMimetype(name)
	}

	@Override
	void setContentType(String contentType) {
		// Content Type is not implemented in most file system stores
		return
	}

	@Override
	Boolean exists() {
		return baseFile.exists()
	}


	def save(acl = '') {
		// Auto saves
		if(sourceStream) {
			try {
				if(!baseFile.parentFile.exists()) {
					baseFile.parentFile.mkdirs()
				}
			} catch(ex) {
				log.warn("Error ensuring path exists: ${baseFile.parentFile.path} - ${ex.message}...This may be ok though, moving on.")
			}


				copyStream(sourceStream, getOutputStream())
			sourceStream = null
		}
		return
	}

	@CompileStatic
	private copyStream(InputStream source, OutputStream out) {

		try {
			source.transferTo(out)
		} finally {
			try {
				out.flush()
				out.close()
			} catch(ex) {}
			source.close()
		}
	}

	@Override
	def delete() {
		if(baseFile.exists()) {
			if(baseFile.isDirectory()) {
				parent.listFiles(prefix: name + "/",delimiter: "/")?.each { subFile ->
					subFile.delete()
				}
				if(baseFile.exists()) {
					try {
						baseFile.delete()
					} catch (Exception e) {
						//this probably walked up the tree.
					}

				}

			} else {
				baseFile.delete()
			}

		}
		cleanUpTree()
	}

	private cleanUpTree() {
		Nfs3File parentDir =  baseFile.parentFile
		while(parentDir.absolutePath != parent.baseFile.absolutePath) {
			if(parentDir.list().size() == 0) {
				parentDir.delete()
				parentDir = parentDir.parentFile
			} else {
				break
			}
		}
	}

	void setMetaAttribute(key, value) {
		log.warn("Karman CloudFile Meta Attributes Not Available for NfsCloudFile")
	}

	def getMetaAttribute(key) {
		log.warn("Karman CloudFile Meta Attributes Not Available for NfsCloudFile")
	}

	def getMetaAttributes() {
		log.warn("Karman CloudFile Meta Attributes Not Available for NfsCloudFile")
	}

	void removeMetaAttribute(key) {
		log.warn("Karman CloudFile Meta Attributes Not Available for NfsCloudFile")
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	boolean supportsRangeBasedInputStream() {
		return true
	}

	@Override
	InputStream getInputStream(long offset, long length) {
		if(baseFile.exists()) {
			return new NfsFileInputStream(baseFile, offset)
		}
		return null
	}
}
