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

package com.bertramlabs.plugins.karman

import java.nio.file.Files
import java.nio.file.Path
import groovy.transform.CompileStatic

/**
* Provides a standardized interface for dealing with files stored in the cloud.
* Several methods exist on the CloudFile object to provide similar interactions as one
* might expect when using a standard java File class.
* <p>
* Below is an example of how a CloudFile might be used. Typically you would not use this class directly but rather use an implementation of this class. (i.e. {@link com.bertramlabs.plugins.karman.local.LocalFile})
* </p>
* <pre>
* CloudFile file = new CloudFile(provider: provider, parent: directory , name: name)
*
* file.text = "Setting test Content"
* file.contentType = "text/plain"
* if(!file.exists()) {
*     file.save()
* }
* </pre>
* @author David Estes
*/
abstract class CloudFile implements CloudFileInterface {
	StorageProvider provider

	/**
	* Property for setting the file name or retrieving.
	*/
	String name

	/**
	* Checks if the file is of type file (should always return true)
	*/
	Boolean isFile() {
		return true
	}

	/**
	* Default implementation for getURL will return nothing. This has to be implemented by each provider type
	* @param expirationDate the expire time of the URL being requested (if supported). Defaults to null
	*/
	URL getURL(Date expirationDate = null) {
		return null
	}

	/**
	* Sets the contents of the file using a String. This is a chainable method that returns an instance of itself
	* @param content String containing content that you wish to save to the file
	*/
	CloudFile text(String content) {
		setText(content)
		return this
	}

	void setContentLength(Long length) {
		//Do nothing
	}


	/**
	* Sets the contentType of the file. This is a chainable method that returns an instance of itself.
	* @param type String used to identify the type of content (i.e. image/png)
	*/
	CloudFile contentType(String type) {
		setContentType(type)
		return this
	}


	/**
	* Checks if the file is of type file or of type directory, since this is a CloudFile it should always return false
	*/
	Boolean isDirectory() {
		return false
	}

	/**
	* Returns the name of the file
	*/
	String toString() {
		return name
	}

	/**
	* Saves the file using the defaultFileACL as configured in the provider
	*/
	def save() {
		save(provider.defaultFileACL)
	}

	/**
	 * This method is used for storing file contents to a temporary on disk file so the final ContentLength can be assessed
	 * before being uploaded to a target cloud
	 * @param inputStream
	 * @return
	 */
	@CompileStatic
	protected File cacheStreamToFile(String name, InputStream inputStream) {
		Path temporaryDirectory
		File tempFile
		OutputStream out
		 try {
			 
			 File tempDirFile = new File(provider.tempDir ?: KarmanConfigHolder.getTempDir() ?: '/tmp')
			 if(!tempDirFile.exists()) {
				 tempDirFile.mkdirs()
			 }
			 temporaryDirectory = tempDirFile.toPath()
		 
			 Path tempFilePath = Files.createTempFile(temporaryDirectory,name,null)
			 tempFile = tempFilePath.toFile()

			 byte[] buffer = new byte[8192*2];
			 int len;
			 out = tempFile.newOutputStream()
			 while ((len = inputStream.read(buffer)) != -1) {
				 out.write(buffer, 0, len);
			 }

		 } catch(ex) {
		 	if(tempFile) {
				 tempFile.delete()
			}
			throw ex
		 } finally {
		 	if(out) {
				 try { out.flush() ; out.close()}  catch(ex2) {}
			}
			
			if(inputStream) {
			 try { inputStream.close()} catch(ex3) {}
			}
		 }

		return tempFile
	}

	protected cleanupCacheStream(File file) {
		if(file && file.exists()) {
			file.delete()
		}
	}

	@Override
	InputStream getInputStream(long offset, long length) {
		throw new UnsupportedOperationException('Range based input streams are not supported.')
	}

	@Override
	boolean supportsRangeBasedInputStream() {
		return false
	}
}
