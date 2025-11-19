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

/**
* An Interface for interacting with Cloud Files. If looking to add new cloud providers,
* one must implement this interface via the {@link com.bertramlabs.plugins.karman.CloudFile} abstract class.
* @author David Estes
*/
interface CloudFileInterface {

	String getName()

	URL getURL(Date expirationDate)
	URL getURL()

	InputStream getInputStream()
	default InputStream getInputStream(long offset,long length) {
		throw new IllegalArgumentException("Not supported by this provider")
	}
	void setInputStream(InputStream is)
	OutputStream getOutputStream()

	String getText(String encoding)
	String getText()

	Directory getParent()

	byte[] getBytes()

	void setText(String text)

	void setBytes(bytes)

	Long getContentLength()

	void setContentLength(Long length)

	String getContentType()

	Date getDateModified()

	void setContentType(String contentType)

	Boolean exists()

	Boolean isFile()

	Boolean isDirectory()

	def save()

	def save(acl)

	def delete()

	void setMetaAttribute(key, value)

	def getMetaAttribute(key)

	def getMetaAttributes()

	void removeMetaAttribute(key)

	default boolean isChunkable() {
		return false
	}


}
