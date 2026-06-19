package com.bertramlabs.plugins.karman.differential

import com.bertramlabs.plugins.karman.CloudFile
import com.bertramlabs.plugins.karman.CloudFileACL
import com.bertramlabs.plugins.karman.CloudFileInterface
import com.bertramlabs.plugins.karman.StorageProvider
import com.bertramlabs.plugins.karman.util.Mimetypes
import groovy.transform.CompileStatic
import groovy.util.logging.Commons
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import com.bertramlabs.plugins.karman.util.BoundedExecutor

@Commons
public class DifferentialCloudFile extends CloudFile {
	CloudFileInterface sourceFile
	DifferentialDirectory parent
	InputStream rawSourceStream = null
	private Long internalContentLength;
	private boolean internalContentLengthSet = false;
	private DifferentialCloudFile linkedFile = null
	private BoundedExecutor blockWriteExecutor = new BoundedExecutor(java.util.concurrent.Executors.newFixedThreadPool(4),4)
	private Long onDeviceContentLength = null

	/**
	 * Optional dirty ranges for sparse-aware save. When set, blocks entirely outside these
	 * ranges are assumed unchanged from the linked file and their manifest entries are copied
	 * directly without reading or hashing the input data for those blocks.
	 * Each range is a map with 'offset' (Long) and 'length' (Long) in bytes.
	 */
	private List<Map> dirtyRanges = null

	void setDirtyRanges(List<Map> ranges) {
		this.dirtyRanges = ranges
	}
	DifferentialCloudFile(String name, DifferentialDirectory parent, CloudFileInterface sourceFile) {
		this.name = name
		this.provider = parent.provider
		this.parent = parent
		this.sourceFile = sourceFile
	}

	@Override
	@CompileStatic
	InputStream getInputStream() {
		CloudFileInterface manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			//we need to copy the index table to local storage for read in case of slow connections
			Path localManifestCache = Files.createTempFile("karman",".diff")
			File manifestLocalFile = localManifestCache.toFile()
			manifestFile.getInputStream().withStream { InputStream is ->
				manifestLocalFile.withOutputStream { OutputStream os ->
					os << is
				}
			}
			return new DifferentialInputStream(sourceFile, manifestLocalFile)
		} else {
			return sourceFile.getInputStream()
		}
	}

	@Override
	Boolean isDirectory() {
		CloudFile manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			return false
		}
		return sourceFile.isDirectory()
	}

	@Override
	void setInputStream(InputStream is) {
		rawSourceStream = is
		//differential store...we gotta do something special here
	}

	@Override
	OutputStream getOutputStream() {
		return null
	}

	@Override
	String getText(String encoding) {
		return getInputStream().getText(encoding)
	}

	@Override
	String getText() {
		return getInputStream().text
	}


	@Override
	byte[] getBytes() {
		return getInputStream().bytes
	}

	@Override
	void setText(String text) {
		setInputStream(new ByteArrayInputStream(text.bytes))
	}

	@Override
	void setBytes(bytes) {
		setInputStream(new ByteArrayInputStream(bytes))
	}

	@Override
	Long getContentLength() {
		CloudFile manifestFile = parent[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			DifferentialInputStream is = null
			try {
				is = new DifferentialInputStream(sourceFile, manifestFile.getInputStream())
				if(is.manifestData.fileSize != null) {
					return is.manifestData.fileSize
				} else {
					long contentLength = 0
					ManifestData.BlockData currentBlock = is.getNextBlockData()
					while(currentBlock != null) {
						contentLength += currentBlock.blockSize
						currentBlock = is.getNextBlockData()
					}
					return contentLength
				}
			} finally {
				try {
					if(is != null) {
						is.close()
					}
				} catch(ignore) {
					//ignore
				}
			}
		} else {
			return sourceFile.getContentLength()
		}
	}

	Long getOnDeviceContentLength() {
		CloudFile manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			DifferentialInputStream is = null
			try {
				long contentLength = manifestFile.contentLength
				if(onDeviceContentLength) {
					contentLength += onDeviceContentLength
				} else {
					is = new DifferentialInputStream(sourceFile, manifestFile.getInputStream())
					ManifestData.BlockData currentBlock = is.getNextBlockData()
					while(currentBlock != null) {
//					contentLength += currentBlock.blockSize //this is the uncompressed size and is not accurate
						if(currentBlock.fileIndex == 0 && !currentBlock.zeroFilled) {
							String blockFilePath = ManifestData.BlockData.getBlockPath(sourceFile, currentBlock.block, 0, is.manifestData);
							CloudFile blockFile = parent.sourceDirectory[blockFilePath]
							contentLength += blockFile.contentLength
						}
						currentBlock = is.getNextBlockData()
					}
				}

				return contentLength
			} finally {
				try {
					if(is != null) {
						is.close()
					}

				} catch(ignore) {
					//ignore
				}
			}



		} else {
			return sourceFile.getContentLength()
		}
	}

	@Override
	String getContentType() {
		return Mimetypes.instance.getMimetype(name)
	}

	@Override
	Date getDateModified() {
		CloudFile manifestFile = parent[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			return manifestFile.getDateModified()
		} else {
			return sourceFile.getDateModified()
		}
	}

	@Override
	void setContentType(String contentType) {
		// Content Type is not implemented in most file system stores
	}

	@Override
	void setContentLength(Long length) {
		internalContentLength = length
		internalContentLengthSet = true
		CloudFile manifestFile = parent[sourceFile.name + "/karman.diff"]
		if(!manifestFile.exists()) {
			sourceFile.setContentLength(length)
		}

	}

	@Override
	Boolean exists() {
		try {
			CloudFile manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
			if(manifestFile.exists()) {
				return true
			}
		} catch(Exception e) {
			//ignore
		}

		return sourceFile.exists()
	}

	@Override
	@CompileStatic
	def save(acl) {
		CloudFileInterface manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		DifferentialInputStream diffInput = null
		File manifestLocalFile=null

		if(!sourceFile.exists() || manifestFile.exists()) {
			try {
				if(manifestFile.exists())
					manifestFile.delete()

				Path localManifestCache = Files.createTempFile("karman",".diff")
				manifestLocalFile = localManifestCache.toFile()
				onDeviceContentLength = 0l
				ManifestData manifestData = new ManifestData()
				manifestData.fileSize = internalContentLength
				manifestData.fileName = sourceFile.name
				manifestData.blockSize = ((DifferentialStorageProvider) provider).blockSize
				if(linkedFile != null) {
					CloudFileInterface linkedManifest = parent.sourceDirectory[linkedFile.name + "/karman.diff"]
					if(linkedManifest.exists()) {
						Path localDifferentialCache = Files.createTempFile("karman-linked",".diff")
						File localDifferentialFile = localDifferentialCache.toFile()
						linkedManifest.getInputStream().withStream { InputStream is ->
							localDifferentialFile.withOutputStream { OutputStream os ->
								os << is
							}
						}
						diffInput = new DifferentialInputStream(linkedFile, localDifferentialFile)

						manifestData.sourceFiles = diffInput.manifestData.sourceFiles
						if(manifestData.sourceFiles == null) {
							manifestData.sourceFiles = []
						}
						manifestData.sourceFiles = [linkedFile.name] + manifestData.sourceFiles
					}

				}

				// Use sparse-aware path when dirty ranges are provided and we have a linked file
				if(dirtyRanges != null && diffInput != null && internalContentLength != null) {
					saveSparseWithDirtyRanges(manifestData, diffInput, localManifestCache, manifestFile)
				} else {
					saveFullStream(manifestData, diffInput, localManifestCache, manifestFile)
				}
			} finally {
				if(diffInput != null) {
					try {
						diffInput.close()
					} catch(Exception ignore) {

					}
				}
				if(manifestLocalFile?.exists()) {
					manifestLocalFile.delete() //clean up temp file
				}
			}


		} else {
			sourceFile.save(acl)
		}
	}

	/**
	 * Standard full-stream save: reads the entire input, hashes each block, stores changed blocks.
	 */
	@CompileStatic
	private void saveFullStream(ManifestData manifestData, DifferentialInputStream diffInput, Path localManifestCache, CloudFileInterface manifestFile) {
		String headerString = manifestData.getHeader()
		OutputStream pos = localManifestCache.newOutputStream()
		pos.write(headerString.getBytes())

		BlockDigestStream dataStream = new BlockDigestStream(rawSourceStream, pos, manifestData.blockSize, diffInput)
		byte[] buffer = new byte[manifestData.blockSize]
		int bytesRead = 0
		long blockNumber = 0

		while((bytesRead = dataStream.read(buffer)) != -1) {
			boolean allZero = true
			for(byte b : buffer) {
				if(b != 0) {
					allZero = false
					break
				}
			}

			if(!allZero && dataStream.lastBlockDifferent) {
				ByteArrayOutputStream compressedBuffer = new ByteArrayOutputStream()
				GZIPOutputStream xz = new GZIPOutputStream(compressedBuffer)
				xz.write(buffer, 0, bytesRead)
				xz.finish()
				String blockFilePath = ManifestData.BlockData.getBlockPath(sourceFile, blockNumber, 0, manifestData);
				byte[] data = compressedBuffer.toByteArray()
				asyncWriteBlock(blockFilePath,data)
				onDeviceContentLength += data.size()
			}
			blockNumber++
		}
		pos.flush()
		pos.close()
		InputStream localFileStream = localManifestCache.toFile().newInputStream()
		manifestFile.setInputStream(localFileStream)
		manifestFile.save()
		localFileStream.close()
	}

	/**
	 * Sparse-aware save: only reads and hashes blocks that overlap with dirty ranges.
	 * Blocks entirely outside dirty ranges are assumed unchanged from the linked file —
	 * their manifest entries are copied with fileIndex incremented.
	 */
	@CompileStatic
	private void saveSparseWithDirtyRanges(ManifestData manifestData, DifferentialInputStream diffInput, Path localManifestCache, CloudFileInterface manifestFile) {
		int blockSize = manifestData.blockSize
		long fileSize = internalContentLength.longValue()
		long totalBlocks = (long)((fileSize + (long)blockSize - 1L).intdiv((long)blockSize))

		String headerString = manifestData.getHeader()
		OutputStream pos = localManifestCache.newOutputStream()
		pos.write(headerString.getBytes())

		// Pre-compute which blocks are dirty
		BitSet dirtyBlockMap = new BitSet((int)totalBlocks)
		for(Map range : dirtyRanges) {
			long rangeOffset = ((Number)range.get("offset")).longValue()
			long rangeLength = ((Number)range.get("length")).longValue()
			long startBlock = (long)(rangeOffset.intdiv((long)blockSize))
			long endBlock = (long)((rangeOffset + rangeLength - 1L).intdiv((long)blockSize))
			for(long b = startBlock; b <= endBlock && b < totalBlocks; b++) {
				dirtyBlockMap.set((int)b)
			}
		}

		// Read linked file's block manifest entries
		List<ManifestData.BlockData> linkedBlocks = new ArrayList<ManifestData.BlockData>((int)totalBlocks)
		for(int i = 0; i < totalBlocks; i++) {
			ManifestData.BlockData bd = diffInput.getNextBlockData()
			linkedBlocks.add(bd)
		}

		// Process each block
		byte[] buffer = new byte[blockSize]
		java.security.MessageDigest shaDigest
		try {
			shaDigest = java.security.MessageDigest.getInstance("SHA3-224")
		} catch(java.security.NoSuchAlgorithmException e) {
			shaDigest = java.security.MessageDigest.getInstance("SHA-256")
		}

		for(long blockNumber = 0; blockNumber < totalBlocks; blockNumber++) {
			int currentBlockSize = (int)Math.min((long)blockSize, fileSize - (blockNumber * (long)blockSize))

			if(!dirtyBlockMap.get((int)blockNumber) && linkedBlocks[(int)blockNumber] != null) {
				// Clean block: copy manifest entry from linked file with incremented fileIndex
				ManifestData.BlockData linkedBlock = linkedBlocks[(int)blockNumber]
				ManifestData.BlockData newBlock = new ManifestData.BlockData()
				newBlock.block = blockNumber
				newBlock.blockSize = linkedBlock.blockSize
				newBlock.hash = linkedBlock.hash
				newBlock.fileIndex = linkedBlock.fileIndex + 1
				pos.write(newBlock.generateBytes())

				// Skip this block's worth of data from the input stream
				long toSkip = currentBlockSize
				while(toSkip > 0) {
					long skipped = rawSourceStream.skip(toSkip)
					if(skipped <= 0) {
						int toRead = (int)Math.min(toSkip, (long)buffer.length)
						int read = rawSourceStream.read(buffer, 0, toRead)
						if(read <= 0) break
						toSkip -= read
					} else {
						toSkip -= skipped
					}
				}
			} else {
				// Dirty block: read, hash, and potentially store
				int offset = 0
				while(offset < currentBlockSize) {
					int read = rawSourceStream.read(buffer, offset, currentBlockSize - offset)
					if(read == -1) break
					offset += read
				}
				int bytesRead = offset

				boolean allZero = true
				for(int i = 0; i < bytesRead; i++) {
					if(buffer[i] != 0) { allZero = false; break }
				}

				shaDigest.reset()
				shaDigest.update(buffer, 0, bytesRead)
				byte[] hash = shaDigest.digest()

				ManifestData.BlockData newBlock = new ManifestData.BlockData()
				newBlock.block = blockNumber
				newBlock.blockSize = bytesRead
				newBlock.hash = allZero ? new byte[hash.length] : hash
				newBlock.fileIndex = 0

				// Check if hash matches linked block
				if(!allZero && linkedBlocks[(int)blockNumber] != null) {
					ManifestData.BlockData linkedBlock = linkedBlocks[(int)blockNumber]
					if(Arrays.equals(hash, linkedBlock.hash)) {
						newBlock.fileIndex = linkedBlock.fileIndex + 1
					}
				}

				pos.write(newBlock.generateBytes())

				// Store block data if changed
				if(!allZero && newBlock.fileIndex == 0) {
					ByteArrayOutputStream compressedBuffer = new ByteArrayOutputStream()
					GZIPOutputStream gzOut = new GZIPOutputStream(compressedBuffer)
					gzOut.write(buffer, 0, bytesRead)
					gzOut.finish()
					String blockFilePath = ManifestData.BlockData.getBlockPath(sourceFile, blockNumber, 0, manifestData)
					byte[] data = compressedBuffer.toByteArray()
					asyncWriteBlock(blockFilePath, data)
					onDeviceContentLength += data.size()
				}
			}
		}

		pos.flush()
		pos.close()
		InputStream localFileStream = localManifestCache.toFile().newInputStream()
		manifestFile.setInputStream(localFileStream)
		manifestFile.save()
		localFileStream.close()
	}

	private Date saturactionNotify = null

	@CompileStatic
	protected asyncWriteBlock(String blockFilePath, byte[] data) {
		if(blockWriteExecutor.availablePermits() == 0) {
			if(saturactionNotify == null || (new Date().time - saturactionNotify.time) > 60000) {
				saturactionNotify = new Date()
				log.info("Block write executor is saturated, max throughput is being achieved...")
			}
		}
		blockWriteExecutor.submit(new AsyncBlockWriter(blockFilePath, data))
	}

	@CompileStatic
	private class AsyncBlockWriter implements Callable<Boolean> {
		String blockFilePath
		byte[] data
		AsyncBlockWriter(String blockFilePath, byte[] data) {
			this.blockFilePath = blockFilePath
			this.data = data
		}
/**
 * Computes a result, or throws an exception if unable to do so.
 *
 * @return computed result
 * @throws Exception if unable to compute a result
 */

		@Override
		Boolean call() throws Exception {
			int attempts=0
			Boolean success = false
			try {
				while(attempts < 5) {
					try {

						CloudFileInterface blockFile = parent.sourceDirectory[blockFilePath]

						blockFile.setContentLength(data.size())
						blockFile.setInputStream(new ByteArrayInputStream(data));
						blockFile.save()
						success = true
						break
					} catch(Exception e) {
						attempts++
						sleep(5000l*attempts + 5000l)

						if(attempts == 5) {
							log.error("Error saving block file...Max Attempts Reached...",e)
							throw new Exception("Error saving block file...Max Attempts Reached...",e)
						} else {
							log.error("Error saving block file...sleeping and trying again shortly...",e)
						}
					}

				}
			} catch(Exception ie) {
				log.error("Error Saving Data to " + blockFilePath,ie);
			}


			return success
		}
	}

	@Override
	def delete() {
		CloudFile manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			parent.sourceDirectory.listFiles(prefix: sourceFile.name + "/", delimiter: "/")?.each { CloudFileInterface file ->
				file.delete()
			}
			if(sourceFile.exists()) {
				try {
					sourceFile.delete()
				} catch(Exception ex) {
					//trying to delete but could have recursively cleaned from the previous loop
					log.debug("Unable to delete root directory of differential file...this may have already been cleaned up from recursion...ignoring",ex)
				}

			}

		} else {
			if(sourceFile.exists()) {
				sourceFile.delete()
			}
		}

	}

	@Override
	void setMetaAttribute(key, value) {
		CloudFile manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			manifestFile.setMetaAttribute(key, value)
		} else {
			sourceFile.setMetaAttribute(key, value)
		}
	}

	@Override
	String getMetaAttribute(key) {
		CloudFile manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			return manifestFile.getMetaAttribute(key)
		} else {
			return sourceFile.getMetaAttribute(key)
		}
	}

	@Override
	Map getMetaAttributes() {
		CloudFile manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			return manifestFile.getMetaAttributes()
		} else {
			return sourceFile.getMetaAttributes()
		}
	}

	@Override
	void removeMetaAttribute(key) {
		CloudFile manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		if(manifestFile.exists()) {
			manifestFile.removeMetaAttribute(key)
		} else {
			sourceFile.removeMetaAttribute(key)
		}
	}

	void setLinkedFile(DifferentialCloudFile linkedFile) {
		this.linkedFile = linkedFile
	}

	@CompileStatic
	void flatten(List<DifferentialCloudFile> children = null) {
		CloudFile manifestFile = parent.sourceDirectory[sourceFile.name + "/karman.diff"]
		List<String> sourceFilesToUnlink = []
		//only do this if it is indeed a differential file
		if(manifestFile.exists()) {
			DifferentialInputStream unflattenedStream = null
			OutputStream pos
			File manifestLocalFile=null
			try {
					Path localManifestCache = Files.createTempFile("karman",".diff")
					manifestLocalFile = localManifestCache.toFile()
					CloudFile originalManifest = parent.sourceDirectory[sourceFile.name + "/karman.diff2"]
					originalManifest.setInputStream(manifestFile.getInputStream())
					originalManifest.save()
					Path localOriginalManifestCache = Files.createTempFile("karman2",".diff")
					File manifestOriginalLocalFile = localOriginalManifestCache.toFile()
					originalManifest.getInputStream().withStream { InputStream is ->
					manifestOriginalLocalFile.withOutputStream { OutputStream os ->
							os << is
						}
					}
					unflattenedStream = new DifferentialInputStream(sourceFile, manifestOriginalLocalFile)
					pos = manifestLocalFile.newOutputStream()




					ManifestData manifestData = new ManifestData()
					manifestData.fileSize = unflattenedStream.manifestData.fileSize
					manifestData.fileName = unflattenedStream.manifestData.fileName
					manifestData.blockSize = unflattenedStream.manifestData.blockSize
					sourceFilesToUnlink = unflattenedStream.manifestData.sourceFiles
					manifestData.sourceFiles = null //clear source files

					String headerString = manifestData.getHeader()
					pos.write(headerString.getBytes())

					ManifestData.BlockData currentBlock = unflattenedStream.getNextBlockData()
					while(currentBlock != null) {
						if(currentBlock.fileIndex != 0) {
							if(!currentBlock.zeroFilled) {
								//block file index is not 0 so we need to grab it and pull it in
								String originalBlockFilePath = ManifestData.BlockData.getBlockPath(sourceFile, currentBlock.block, currentBlock.fileIndex, unflattenedStream.manifestData)
								String newBlockFilePath = ManifestData.BlockData.getBlockPath(sourceFile, currentBlock.block, 0, manifestData)

								int attempts=0
								while(attempts < 5) {
									try {
										CloudFileInterface blockFile = parent.sourceDirectory[originalBlockFilePath]
										CloudFileInterface destBlockFile = parent.sourceDirectory[newBlockFilePath]
										if(destBlockFile.exists()) {
											destBlockFile.delete()
										}
										asyncWriteBlock(newBlockFilePath,blockFile.getBytes())
//										destBlockFile.setInputStream(blockFile.getInputStream())
//										destBlockFile.save()
										break
									} catch(Exception e) {
										attempts++
										sleep(5000l*attempts + 5000l)

										if(attempts == 5) {
											log.error("Error saving block file...Max Attempts Reached...",e)
											throw new Exception("Error saving block file...Max Attempts Reached...",e)
										} else {
											log.error("Error saving block file...sleeping and trying again shortly...",e)
										}
									}
								}


							}
							currentBlock.fileIndex = 0
						}
						pos.write(currentBlock.generateBytes())
						currentBlock = unflattenedStream.getNextBlockData()
					}
					//lets make sure executor is done

					pos.flush()
					pos.close()
					pos = null //clear it for finally block unless exception occurs
					InputStream sourceManifestIs = manifestLocalFile.newInputStream()
					if(manifestFile.exists()) {
						//lets delete the old manifest file first
						manifestFile.delete()
					}
					manifestFile.setInputStream(sourceManifestIs)
					manifestFile.save()

					originalManifest.delete()

					//we gotta correct any children from this file based on the child list passed in
					if(children) {
						for(DifferentialCloudFile childrenFile in children) {
							DifferentialInputStream unflattenedChildStream = null
							PipedOutputStream childPos = null
							PipedInputStream childPis = null
							try {
								CloudFile childManifestFile = parent.sourceDirectory[childrenFile.name + "/karman.diff"]
								if(childManifestFile.exists()) {
									CloudFile originalChildManifest = parent.sourceDirectory[childrenFile.name + "/karman.diff2"]
									originalChildManifest.setInputStream(childManifestFile.getInputStream())
									originalChildManifest.save()
									unflattenedChildStream = new DifferentialInputStream(childrenFile.sourceFile, originalChildManifest.getInputStream())
									childPos = new PipedOutputStream()
									childPis = new PipedInputStream(childPos)
									Thread flattenThread = Thread.start {
										childManifestFile.setInputStream(childPis)
										childManifestFile.save()
									}

									ManifestData childManifestData = new ManifestData()
									childManifestData.fileSize = unflattenedChildStream.manifestData.fileSize
									childManifestData.fileName = unflattenedChildStream.manifestData.fileName
									childManifestData.blockSize = unflattenedChildStream.manifestData.blockSize
									childManifestData.sourceFiles = unflattenedChildStream.manifestData.sourceFiles
									def fileIndicesToUnlink = []

									if(sourceFilesToUnlink) {
										sourceFilesToUnlink.each { sourceFileToUnlink ->
											Integer idx = childManifestData.sourceFiles?.indexOf(sourceFileToUnlink)
											if(idx != null && idx >= 0) {
												fileIndicesToUnlink << idx + 1
											}
										}
										childManifestData.sourceFiles?.removeAll(sourceFilesToUnlink)

									}
									int targetIndex = childManifestData.sourceFiles.indexOf(sourceFile.name) + 1
									String childHeaderString = childManifestData.getHeader()
									childPos.write(childHeaderString.getBytes())
									ManifestData.BlockData childCurrentBlock = unflattenedChildStream.getNextBlockData()
									while(childCurrentBlock != null) {
										if(fileIndicesToUnlink.contains(childCurrentBlock.fileIndex)) {
											childCurrentBlock.fileIndex = targetIndex
										}
										childPos.write(childCurrentBlock.generateBytes())
										childCurrentBlock = unflattenedChildStream.getNextBlockData()
									}


									originalChildManifest.delete()
									if(childPos != null) {
										try {
											childPos.flush()
											childPos.close()
										} catch(ignore) {

										}
									}
									flattenThread.join()
								}
							} finally {
								if(childPos != null) {
									try {
										childPos.flush()
										childPos.close()
									} catch(ignore) {

									}
								}
								if(unflattenedStream != null) {
									try {
										unflattenedChildStream.close()
									} catch(ignore) {

									}
								}
							}

						}
					}

			} finally {
					try {
						unflattenedStream.close()
					} catch(ignore) {

					}
					try {
						if(pos != null) {
							pos.flush()
							pos.close()
						}
					} catch(ignore) {

					}
					if(manifestLocalFile?.exists()) {
						manifestLocalFile.delete()
					}
				}
		}
	}
}
