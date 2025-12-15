package com.bertramlabs.plugins.karman.differential;



import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;


public class BlockDigestStream extends InputStream {
    //get commons logger
    private static final org.apache.commons.logging.Log log = org.apache.commons.logging.LogFactory.getLog(BlockDigestStream.class);
    InputStream sourceStream;
    MessageDigest shaDigest;
    InputStream digestStream;
    int blockSize;
    OutputStream manifestOutput;
    long currentBlock=0;
    ManifestData.BlockData blockData;
    DifferentialInputStream linkedFileStream;
    ManifestData.BlockData linkedBlockData = null;
    public BlockDigestStream(InputStream sourceStream, OutputStream manifestOutput, int blockSize, DifferentialInputStream linkedFileStream) throws NoSuchAlgorithmException {
        this.sourceStream = sourceStream;// new BufferedInputStream(sourceStream,8192);
        this.blockSize = blockSize;
        this.manifestOutput = manifestOutput;
        this.linkedFileStream = linkedFileStream;
        blockData = new ManifestData.BlockData();
        blockData.block = currentBlock;
        blockData.fileIndex = 0;
        shaDigest = MessageDigest.getInstance("SHA-256");
        if(linkedFileStream != null) {
            try {
                linkedBlockData = linkedFileStream.getNextBlockData();
            } catch (IOException e) {
                e.printStackTrace();
                //ignore
            }
        }

    }

    /**
     * Reads the next byte of data from the input stream. The value byte is
     * returned as an {@code int} in the range {@code 0} to
     * {@code 255}. If no byte is available because the end of the stream
     * has been reached, the value {@code -1} is returned. This method
     * blocks until input data is available, the end of the stream is detected,
     * or an exception is thrown.
     *
     * <p> A subclass must provide an implementation of this method.
     *
     */
    public boolean lastBlockDifferent = true;
    private boolean zeroFilled = true;
    @Override
    public int read() throws IOException {
        lastBlockDifferent=true;
        int c = sourceStream.read();
        if(c>0) {
            zeroFilled = false;
        }
        if(c >= 0) {
            shaDigest.update((byte)c);

            bytesRead++;
            if(bytesRead % blockSize == 0) {
                if(zeroFilled) {
                    blockData.zeroFilled = true;
                    blockData.hash = new byte[32];
                } else {
                    blockData.hash = shaDigest.digest();
                }

                blockData.blockSize = blockSize;
                if(linkedBlockData != null) {

                    if(linkedBlockData.block == currentBlock) {


                        //check if hash byte array matches

                        if(Arrays.equals(linkedBlockData.hash, blockData.hash)) {
                            blockData.fileIndex = linkedBlockData.fileIndex+1;
//                            log.info("Unchanged Block Detected: " + currentBlock);
                            lastBlockDifferent = false;
                        } else {
                            StringBuilder hexString = new StringBuilder();

                            for (int i = 0; i < linkedBlockData.hash.length; i++) {
                                final String hex = Integer.toHexString(0xff & linkedBlockData.hash[i]);
                                if(hex.length() == 1)
                                    hexString.append('0');
                                hexString.append(hex);
                            }

                            StringBuilder hexString2 = new StringBuilder();

                            for (int i = 0; i < blockData.hash.length; i++) {
                                final String hex = Integer.toHexString(0xff & blockData.hash[i]);
                                if(hex.length() == 1)
                                    hexString2.append('0');
                                hexString2.append(hex);
                            }
//                            log.warn("Hash Comparison: " + hexString + " - " + hexString2);

                        }
                        linkedBlockData = linkedFileStream.getNextBlockData();
                    } else{
//                        log.warn("Somehow block numbers do not match: " + currentBlock + " - " + linkedBlockData.block);
                    }
                }
                manifestOutput.write(blockData.generateBytes());
                currentBlock++;
                bytesRead = 0;
                zeroFilled=true;
                shaDigest.reset();
                blockData = new ManifestData.BlockData();
                blockData.block = currentBlock;
            }
        } else if( bytesRead > 0) {
            blockData.hash = shaDigest.digest();
            blockData.blockSize = (int) bytesRead;
            manifestOutput.write(blockData.generateBytes());
            bytesRead=0;
        }
        return c;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int c = sourceStream.read(buffer, offset, length);
        lastBlockDifferent = true;
        while(c < length && c > 0) { //we want to read the complete block here and not send partials, partials can cause problems
            int c2 = sourceStream.read(buffer, offset+c, length-c);
            if(c2 > 0) {
                c+=c2;
            } else {
                break;
            }
        }
        if(c > 0) {
            bytesRead += c;
            if(bytesRead > blockSize) {
                shaDigest.update(buffer, offset, c - (int) (bytesRead - blockSize));
            } else {
                shaDigest.update(buffer, offset,c);
            }
            if(zeroFilled) {
                for(int i = offset; i < offset+c; i++) {
                    if(buffer[i] != 0) {
                        zeroFilled = false;
                        break;
                    }
                }
            }

            if(bytesRead > blockSize) {
                System.out.println("Greater than block size read");
            }
            if(bytesRead >= blockSize) {


                if(zeroFilled) {
                    blockData.zeroFilled = true;
                    blockData.hash = new byte[32];
                } else {
                    blockData.hash = shaDigest.digest();
                }

                blockData.blockSize = blockSize;
                if(linkedBlockData != null) {
                    if(linkedBlockData.block == currentBlock) {

                        //check if hash byte array matches
                        if(Arrays.equals(linkedBlockData.hash, blockData.hash)) {
                            lastBlockDifferent = false;
                            blockData.fileIndex = linkedBlockData.fileIndex+1;
                        } else {
//                            StringBuilder hexString = new StringBuilder();
//
//                            for (int i = 0; i < linkedBlockData.hash.length; i++) {
//                                final String hex = Integer.toHexString(0xff & linkedBlockData.hash[i]);
//                                if(hex.length() == 1)
//                                    hexString.append('0');
//                                hexString.append(hex);
//                            }
//
//                            StringBuilder hexString2 = new StringBuilder();
//
//                            for (int i = 0; i < blockData.hash.length; i++) {
//                                final String hex = Integer.toHexString(0xff & blockData.hash[i]);
//                                if(hex.length() == 1)
//                                    hexString2.append('0');
//                                hexString2.append(hex);
//                            }
//                            System.out.println("Comparing Hashes: " + hexString + " - " + hexString2);
                        }
                            linkedBlockData = linkedFileStream.getNextBlockData();
                    }
                }
                manifestOutput.write(blockData.generateBytes());
                currentBlock++;
                bytesRead = bytesRead-blockSize;
                shaDigest.reset();
                zeroFilled=true;
                if(bytesRead > 0) {
                    shaDigest.update(buffer, offset + c - (int) bytesRead, (int) bytesRead);
                }
                blockData = new ManifestData.BlockData();
                blockData.block = currentBlock;
            }
        } else if( bytesRead > 0) {
            if(zeroFilled) {
                blockData.zeroFilled = true;
                blockData.hash = new byte[32];
            } else {
                blockData.hash = shaDigest.digest();
            }

            blockData.blockSize = (int) bytesRead;
            manifestOutput.write(blockData.generateBytes());
            bytesRead=0;
        }
        return c;
    }





    long bytesRead=0;
}
