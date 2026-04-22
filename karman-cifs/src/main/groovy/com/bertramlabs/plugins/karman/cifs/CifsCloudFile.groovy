/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bertramlabs.plugins.karman.cifs

import com.bertramlabs.plugins.karman.*
import groovy.util.logging.Commons
import java.io.ByteArrayInputStream;
import com.bertramlabs.plugins.karman.util.Mimetypes
import groovy.transform.CompileStatic
import jcifs.smb.SmbFile

@Commons
class CifsCloudFile extends CloudFile {

	CifsDirectory parent
	CifsStorageProvider provider
	InputStream sourceStream
	SmbFile baseFile
	SmbFile getCifsFile() {
		if(baseFile) {
			return baseFile
		}
		def rtn
		def parentFile = parent.getCifsFile()
		// def path = parentFile.path + '/' + name

		rtn = new SmbFile(parentFile, name)
		baseFile = rtn
		return rtn
	}

	/**
	* Get URL
	* @param expirationDate
	* @return url
	*/
	URL getURL(Date expirationDate = null) {
		new URL("${provider.baseUrl}/${parent.name}/${name}")
	}

	InputStream getInputStream() {
		getCifsFile().getInputStream()
	}

	@CompileStatic
	void setInputStream(InputStream inputS) {
		sourceStream = inputS
	}

	OutputStream getOutputStream() {
		def cifsFile = getCifsFile()
		if(!cifsFile.exists()) {
			ensurePathExists()
			cifsFile.createNewFile()
		}
		cifsFile.getOutputStream()
	}

	String getText(String encoding = null) {
		def result = null
		if(encoding) {
			result = inputStream?.getText(encoding)
		} else {
			result = inputStream?.text
		}
		inputStream?.close()
		return result
	}

	byte[] getBytes() {
		def result = inputStream?.bytes
		inputStream?.close()
		return result
	}

	void setText(String text) {
    setBytes(text.bytes)
	}

	void setBytes(bytes) {
		ensurePathExists()
		def rawSourceStream = new ByteArrayInputStream(bytes)
		setInputStream(rawSourceStream)
	}

	Long getContentLength() {
		def cifsFile = getCifsFile()
		return cifsFile.length()
	}

	Date getDateModified() {
		return new Date(cifsFile.lastModified())
	}


	String getContentType() {
		return Mimetypes.instance.getMimetype(name)
	}

	void setContentType(String contentType) {
		// Content Type is not implemented in most file system stores
		return
	}

	Boolean exists() {
		def cifsFile = getCifsFile()
		return cifsFile.exists()
	}

  void setMetaAttribute(key, value) {
		log.warn("Karman CloudFile Meta Attributes Not Available for LocalCloudFile")
	}

	def getMetaAttribute(key) {
		log.warn("Karman CloudFile Meta Attributes Not Available for LocalCloudFile")
	}

	def getMetaAttributes() {
		log.warn("Karman CloudFile Meta Attributes Not Available for LocalCloudFile")
	}

	void removeMetaAttribute(key) {
		log.warn("Karman CloudFile Meta Attributes Not Available for LocalCloudFile")
	}

	@CompileStatic
  	def save(acl = '') {
		if(sourceStream) {
			SmbFile parentFile = new SmbFile(cifsFile.parent,provider.getCifsContext())
			try {
				if(!parentFile.exists()) {
					parentFile.mkdirs()
				}
			} catch(ex) {
				log.warn("Error ensuring path exists: ${parentFile.path} - ${ex.message}...This may be ok though, moving on.")
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

	def delete() {
		def cifsFile = getCifsFile()
		if(cifsFile.exists()) {
			cifsFile.delete()
		}

		cleanUpTree()
	}

	@Override
	Boolean isFile() {
		def cifsFile = getCifsFile()
		return cifsFile.isFile()
	}

	@Override
	Boolean isDirectory() {
		def cifsFile = getCifsFile()
		return cifsFile.isDirectory()
	}

	private cleanUpTree() {
		def cifsFile = getCifsFile()

		
		SmbFile parentDir =  new SmbFile(cifsFile.parent, provider.getCifsContext())
		while(parentDir.canonicalPath != parent.cifsFile.canonicalPath) {
			if(parentDir.list().size() == 0) {
				parentDir.delete()
				parentDir = new SmbFile(parentDir.parent, provider.getCifsContext())
			} else {
				break
			}
		}
	}

  private ensurePathExists() {
  	SmbFile cifsFile = getCifsFile()
	SmbFile parentFile =  new SmbFile(cifsFile.parent, provider.getCifsContext()) 
    if(!parentFile.exists()) {
    	try {
    		parentFile.mkdirs()	
    	} catch(ex) {
    		log.warn("Error Creating Parent Path Directories, It may be because something tried to write in parallel. If this is the case this error can safely be ignored! ${ex.message}")
    	}
    	
    }
  }

	/**
	 * {@inheritDoc}
	 */
	@Override
	InputStream getInputStream(long offset, long length) {
		return new SmbRandomAccessFileInputStream(getCifsFile(), offset)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	boolean supportsRangeBasedInputStream() {
		return true
	}
}
