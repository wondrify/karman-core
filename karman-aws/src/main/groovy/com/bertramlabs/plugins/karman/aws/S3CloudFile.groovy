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

package com.bertramlabs.plugins.karman.aws

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.Headers
import com.amazonaws.services.s3.model.*
import com.bertramlabs.plugins.karman.CloudFile
import com.bertramlabs.plugins.karman.util.ChunkedInputStream
import org.apache.http.Header
import org.apache.http.HttpEntity
import org.apache.http.HttpHost
import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.ParseException
import org.apache.http.auth.AuthScope
import org.apache.http.auth.NTCredentials
import org.apache.http.client.CredentialsProvider
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.utils.URIBuilder
import org.apache.http.config.MessageConstraints
import org.apache.http.config.Registry
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.ConnectTimeoutException
import org.apache.http.conn.HttpConnectionFactory
import org.apache.http.conn.ManagedHttpClientConnection
import org.apache.http.conn.routing.HttpRoute
import org.apache.http.conn.socket.ConnectionSocketFactory
import org.apache.http.conn.socket.PlainConnectionSocketFactory
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.conn.ssl.X509HostnameVerifier
import org.apache.http.impl.DefaultHttpResponseFactory
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.client.ProxyAuthenticationStrategy
import org.apache.http.impl.conn.BasicHttpClientConnectionManager
import org.apache.http.impl.conn.DefaultHttpResponseParser
import org.apache.http.impl.conn.DefaultHttpResponseParserFactory
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory
import org.apache.http.impl.io.DefaultHttpRequestWriterFactory
import org.apache.http.io.HttpMessageParser
import org.apache.http.io.HttpMessageParserFactory
import org.apache.http.io.HttpMessageWriterFactory
import org.apache.http.io.SessionInputBuffer
import org.apache.http.message.BasicHeader
import org.apache.http.message.BasicLineParser
import org.apache.http.message.LineParser
import org.apache.http.protocol.HttpContext
import org.apache.http.ssl.SSLContexts
import org.apache.http.util.CharArrayBuffer
import org.apache.commons.beanutils.PropertyUtils

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.lang.reflect.InvocationTargetException
import java.security.SecureRandom
import java.security.cert.X509Certificate

class S3CloudFile extends CloudFile {

	S3Directory parent
	S3Object object
	S3ObjectSummary summary // Only set when object is retrieved by listFiles
	InputStream writeableStream
	InputStream rawSourceStream

	private Boolean loaded = false
	private Boolean metaDataLoaded = false
	private Boolean existsFlag = null
	private Long internalContentLength = null
	private Boolean internalContentLengthSet =false
	/**
	 * Meta attributes setter/getter
	 */
	void setMetaAttribute(key, value) {
		switch(key) {
			case Headers.CACHE_CONTROL:
				s3Object.objectMetadata.cacheControl = value
				break
			case Headers.CONTENT_DISPOSITION:
				s3Object.objectMetadata.contentDisposition = value
				break
			case Headers.CONTENT_ENCODING:
				s3Object.objectMetadata.contentEncoding = value
				break
			case Headers.CONTENT_LENGTH:
				s3Object.objectMetadata.contentLength = value
				break
			case Headers.CONTENT_MD5:
				s3Object.objectMetadata.contentMD5 = value
				break
			case Headers.CONTENT_TYPE:
				s3Object.objectMetadata.contentType = value
				break
			case Headers.EXPIRES:
				s3Object.objectMetadata.httpExpiresDate = value
				break
			case Headers.S3_CANNED_ACL:
				s3Object.objectMetadata.setHeader(Headers.S3_CANNED_ACL, value)
				break
			default:
				// User specific meta
				s3Object.objectMetadata.userMetadata[key] = value
		}
	}

	OutputStream getOutputStream() {
		def outputStream = new PipedOutputStream()
		rawSourceStream = new PipedInputStream(outputStream)
		writeableStream = new S3ObjectInputStream(rawSourceStream, null)
		return outputStream
	}

	void setInputStream(InputStream inputS) {
		rawSourceStream = inputS
		writeableStream = new S3ObjectInputStream(rawSourceStream, null)
	}

	String getMetaAttribute(key) {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		s3Object.objectMetadata.userMetadata[key]
	}

	Map getMetaAttributes() {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		s3Object.objectMetadata.userMetadata
	}

	void removeMetaAttribute(key) {
		s3Object.objectMetadata.userMetadata.remove(key)
	}

	/**
	 * Content length metadata
	 */
	Long getContentLength() {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		if(internalContentLengthSet || !exists()) {
			return internalContentLength
		}
		s3Object.objectMetadata.contentLength
	}

	Date getDateModified() {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		if(!exists()) {return null}
		return s3Object.objectMetadata.lastModified
	}

	void setContentLength(Long length) {
		setMetaAttribute(Headers.CONTENT_LENGTH, length)
		internalContentLength = length
		internalContentLengthSet = true
	}

	/**
	 * Content type metadata
	 */
	String getContentType() {
		if(!metaDataLoaded) {
			loadObjectMetaData()
		}
		s3Object.objectMetadata.contentType
	}

	void setContentType(String contentType) {
		setMetaAttribute(Headers.CONTENT_TYPE, contentType)
	}

	/**
	 * Bytes setter/getter
	 */
	byte[] getBytes() {
		def result = inputStream?.bytes
		inputStream?.close()
		return result
	}

	void setBytes(bytes) {
		rawSourceStream = new ByteArrayInputStream(bytes)
		writeableStream = new S3ObjectInputStream(rawSourceStream, null)
		setContentLength(bytes.length)
	}

	/**
	 * Input stream getter
	 * @return inputStream
	 */
	InputStream getInputStream() {
		return getInputStreamInternal()
	}

	private BufferedInputStream getInputStreamInternal(Long offset = null, Long length = null) {
		if (provider.baseUrls && provider.baseUrls[parent.name]) {
			//if we are using a custom base url to fetch it like a cloudfront edge server
			URIBuilder uriBuilder = new URIBuilder("${provider.baseUrls[parent.name]}/${encodedName}".toString())
			HttpGet request = new HttpGet(uriBuilder.build())
			if (offset && length) {
				request.addHeader('Range', "bytes=$offset-${offset+length}")
			}
			HttpClientBuilder clientBuilder = HttpClients.custom()
			clientBuilder.setHostnameVerifier(new X509HostnameVerifier() {
				public boolean verify(String host, SSLSession sess) {
					return true
				}

				public void verify(String host, SSLSocket ssl) {

				}

				public void verify(String host, String[] cns, String[] subjectAlts) {

				}

				public void verify(String host, X509Certificate cert) {

				}

			})
			SSLContext sslcontext = SSLContexts.createSystemDefault()
			//ignoreSSL(sslcontext)
			SSLConnectionSocketFactory sslConnectionFactory = new SSLConnectionSocketFactory(sslcontext) {
				@Override
				public Socket connectSocket(int connectTimeout, Socket socket, HttpHost host, InetSocketAddress remoteAddress, InetSocketAddress localAddress, HttpContext context) throws IOException, ConnectTimeoutException {
					if (socket instanceof SSLSocket) {
						try {
							socket.setEnabledProtocols(['SSLv3', 'TLSv1', 'TLSv1.1', 'TLSv1.2'] as String[])
							PropertyUtils.setProperty(socket, "host", host.getHostName());
						} catch (NoSuchMethodException ex) {
						}
						catch (IllegalAccessException ex) {
						}
						catch (InvocationTargetException ex) {
						}
					}
					return super.connectSocket(30000, socket, host, remoteAddress, localAddress, context)
				}
			}
			HttpMessageParserFactory<HttpResponse> responseParserFactory = new DefaultHttpResponseParserFactory() {

				@Override
				public HttpMessageParser<HttpResponse> create(SessionInputBuffer ibuffer, MessageConstraints constraints) {
					LineParser lineParser = new BasicLineParser() {

						@Override
						public Header parseHeader(final CharArrayBuffer buffer) {
							try {
								return super.parseHeader(buffer);
							} catch (ParseException ex) {
								return new BasicHeader(buffer.toString(), null);
							}
						}

					};
					return new DefaultHttpResponseParser(
							ibuffer, lineParser, DefaultHttpResponseFactory.INSTANCE, constraints ?: MessageConstraints.DEFAULT) {

						@Override
						protected boolean reject(final CharArrayBuffer line, int count) {
							//We need to break out of forever head reads
							if (count > 100) {
								return true
							}
							return false;

						}

					};
				}

			};
			clientBuilder.setSSLSocketFactory(sslConnectionFactory)
			Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory> create()
					.register("https", sslConnectionFactory)
					.register("http", PlainConnectionSocketFactory.INSTANCE)
					.build();

			HttpMessageWriterFactory<HttpRequest> requestWriterFactory = new DefaultHttpRequestWriterFactory();

			HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory = new ManagedHttpClientConnectionFactory(
					requestWriterFactory, responseParserFactory);
			BasicHttpClientConnectionManager connectionManager = new BasicHttpClientConnectionManager(registry, connFactory)

			clientBuilder.setConnectionManager(connectionManager)

			//Proxy Settings
			if (provider.proxyHost) {
				clientBuilder.setProxy(new HttpHost(provider.proxyHost, provider.proxyPort))
				if (provider.proxyUser) {
					CredentialsProvider credsProvider = new BasicCredentialsProvider();
					NTCredentials ntCreds = new NTCredentials(provider.proxyUser, provider.proxyPassword, provider.proxyWorkstation, provider.proxyDomain)
					credsProvider.setCredentials(new AuthScope(provider.proxyHost, provider.proxyPort), ntCreds)

					clientBuilder.setDefaultCredentialsProvider(credsProvider)
					clientBuilder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy())
				}
			}

			HttpClient client = clientBuilder.build()
			HttpResponse response = client.execute(request)
			HttpEntity entity = response.getEntity()
			return new BufferedInputStream(entity.content, 8000)
		} else {
			loadObject(offset, length)
			return new BufferedInputStream(s3Object.objectContent, 8000)
		}
	}

	/**
	 * Text setter/getter
	 * @param encoding
	 * @return text
	 */
	String getText(String encoding = null) {
		def result
		if(encoding) {
			result = inputStream?.getText(encoding)
		} else {
			result = inputStream?.text
		}
		inputStream?.close()
		return result
	}

	void setText(String text) {
		setBytes(text.bytes)
	}

	/**
	 * Get URL or pre-signed URL if expirationDate is set
	 * @param expirationDate
	 * @return url
	 */
	URL getURL(Date expirationDate = null) {
		if(valid) {
			if(provider.baseUrl) {
				return new URL("${provider.baseUrl}/${name}")
			} else if(provider.baseUrls && provider.baseUrls[parent.name]) {
				return new URL("${provider.baseUrls[parent.name]}/${name}")
			} else if(expirationDate) {
				s3Client.generatePresignedUrl(parent.name, name, expirationDate)
			} else {
				new URL("https://${parent.name}.s3.amazonaws.com/${name}")
			}
		}
	}

	/**
	 * Check if file exists
	 * @return true or false
	 */
	Boolean exists() {
		if(valid) {
			if(existsFlag != null) {
				return existsFlag
			}
			if(!name) {
				return false
			}
			//try {
			ObjectListing objectListing = s3Client.listObjects(parent.name, name)
			if(objectListing.objectSummaries) {
				summary = objectListing.objectSummaries.first()
				existsFlag = true
			} else {
				existsFlag = false
			}
			//} catch (AmazonS3Exception exception) {
			//log.warn(exception)
			//} catch (AmazonClientException exception) {
			//log.warn(exception)
			//}
			existsFlag
		} else {
			false
		}
	}

	/**
	 * Save file
	 */
	def save(acl) {
		if(valid) {
			assert writeableStream
			setMetaAttribute(Headers.S3_CANNED_ACL, acl)

			Long contentLength = (internalContentLengthSet || !exists()) ? internalContentLength : object.objectMetadata.contentLength
			if(contentLength != null && contentLength <= provider.chunkSize && parent.provider.forceMultipart == false) {
				s3Client.putObject(parent.name, name, writeableStream, object.objectMetadata)
			} else if(contentLength != null) {
				saveChunked()
			} else {
				File tmpFile = cacheStreamToFile(null,rawSourceStream)
				this.setContentLength(tmpFile.size())
				InputStream is = tmpFile.newInputStream()
				try {
					this.setInputStream(is)
					return this.save(acl)
				} finally {
					if(is) {
						try { is.close()} catch(ex) {}
					}
					cleanupCacheStream(tmpFile)
				}
			}
			object = null
			summary = null
			existsFlag = true
		}
	}

	def saveChunked() {
		Long contentLength = object.objectMetadata.contentLength
		List<PartETag> partETags = new ArrayList<PartETag>();
		InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(parent.name, name);
		initRequest.setObjectMetadata(object.objectMetadata)
		InitiateMultipartUploadResult initResponse =
			s3Client.initiateMultipartUpload(initRequest);
		long partSize = parent.provider.chunkSize; // Set part size to 5 MB.

		if(contentLength && contentLength / 1000l > partSize) {
			partSize = contentLength / 1000l + 1l
		}
		ChunkedInputStream chunkedStream = new ChunkedInputStream(rawSourceStream, partSize)

		long filePosition = 0
		int partNumber = 1
		while(chunkedStream.available() >= 0 && (!contentLength || filePosition < contentLength)) {
			// Last part can be less than 5 MB. Adjust part size.
			if(contentLength) {
				partSize = Math.min(partSize, (contentLength - filePosition));

				// Create request to upload a part.
				UploadPartRequest uploadRequest = new UploadPartRequest()
					.withBucketName(parent.name).withKey(name)
					.withUploadId(initResponse.getUploadId()).withPartNumber(partNumber)
					.withInputStream(chunkedStream)

					.withPartSize(partSize);

				// Upload part and add response to our list.
				partETags.add(s3Client.uploadPart(uploadRequest).getPartETag());
			} else {
				byte[] buff = new byte[partSize]
				def lessThan = false
				int count = chunkedStream.read(buff)
				if(count <= 0) {
					break
				} else if(count < partSize) {
					lessThan = true
				}

				partSize = count
				// Create request to upload a part.
				UploadPartRequest uploadRequest = new UploadPartRequest()
					.withBucketName(parent.name).withKey(name)
					.withUploadId(initResponse.getUploadId()).withPartNumber(partNumber)
					.withInputStream(new ByteArrayInputStream(buff, 0, partSize))

					.withPartSize(partSize);

				// Upload part and add response to our list.
				partETags.add(s3Client.uploadPart(uploadRequest).getPartETag());
				if(lessThan) {
					break
				}
			}


			filePosition += partSize;
			partNumber++
			chunkedStream.nextChunk()
		}
		// Step 3: Complete.
		CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(
			parent.name,
			name,
			initResponse.getUploadId(),
			partETags);

		s3Client.completeMultipartUpload(compRequest);
	}

	/**
	 * Delete file
	 */
	def delete() {
		if(valid) {
			s3Client.deleteObject(parent.name, name)
			existsFlag = false
		}
	}

	// PRIVATE

	private AmazonS3Client getS3Client() {
		parent.provider.s3Client
	}

	private S3Object getS3Object() {
		if(!object) {
			object = new S3Object(bucketName: parent.name, key: name)
			loaded = false
		}
		object
	}

	private void loadObject(Long offset = null, Long length = null) {
		if(valid) {
			def req = new GetObjectRequest(parent.name, name)
			if (offset && length) {
				req.setRange(offset, offset+length)
			}
			object = s3Client.getObject(req)
			loaded = true
			metaDataLoaded = false
		}
	}

	private void loadObjectMetaData() {
		if(valid) {
			s3Object.objectMetadata = s3Client.getObjectMetadata(parent.name, name)
			metaDataLoaded = true
		}
	}

	private boolean isValid() {
		assert parent
		assert parent.name
		assert name
		true
	}

	private String getEncodedName() {
		return java.net.URLEncoder.encode(name, "UTF-8").replaceAll('\\+', '%20')
	}

	private ignoreSSL(SSLContext sslContext) {
		TrustManager[] trustAllCerts = [
			new X509TrustManager() {
				public java.security.cert.X509Certificate[] getAcceptedIssuers() {
					return null;
				}

				public void checkClientTrusted(
					java.security.cert.X509Certificate[] certs, String authType) {
				}

				public void checkServerTrusted(
					java.security.cert.X509Certificate[] certs, String authType) {
				}
			}
		] as TrustManager[]
		// set up a TrustManager that trusts everything
		sslContext.init(null, trustAllCerts, new SecureRandom());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	boolean supportsRangeBasedInputStream() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	InputStream getInputStream(long offset, long length) {
		getInputStreamInternal(offset, length)
	}
}
