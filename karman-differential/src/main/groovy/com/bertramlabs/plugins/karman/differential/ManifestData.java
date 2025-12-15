package com.bertramlabs.plugins.karman.differential;

import com.bertramlabs.plugins.karman.CloudFileInterface;

import java.util.List;

public class ManifestData {
    public String fileName;
    public Long fileSize; //could be unspecified
    public int blockSize; //could be unspecified
    public List<String> sourceFiles;
    public int version = 2;

    public static class BlockData {
        public static final int SIZE = 48;
        long block;
        int fileIndex = 0;
        int blockSize;
        boolean zeroFilled = false;
        byte[] hash;

        public byte[] generateBytes() {
            byte[] bytes = new byte[SIZE];
            byte[] blockBytes = new byte[8];
            byte[] blockSizeBytes = new byte[4];
            byte[] fileIndexBytes = new byte[4];
            System.arraycopy(hash, 0, bytes, 16, 32);
            blockBytes[0] = (byte) (block >> 56);
            blockBytes[1] = (byte) (block >> 48);
            blockBytes[2] = (byte) (block >> 40);
            blockBytes[3] = (byte) (block >> 32);
            blockBytes[4] = (byte) (block >> 24);
            blockBytes[5] = (byte) (block >> 16);
            blockBytes[6] = (byte) (block >> 8);
            blockBytes[7] = (byte) (block);
            System.arraycopy(blockBytes, 0, bytes, 0, 8);
            blockSizeBytes[0] = (byte) (blockSize >> 24);
            blockSizeBytes[1] = (byte) (blockSize >> 16);
            blockSizeBytes[2] = (byte) (blockSize >> 8);
            blockSizeBytes[3] = (byte) (blockSize);
            System.arraycopy(blockSizeBytes, 0, bytes, 8, 4);
            fileIndexBytes[0] = (byte) (fileIndex >> 24);
            fileIndexBytes[1] = (byte) (fileIndex >> 16);
            fileIndexBytes[2] = (byte) (fileIndex >> 8);
            fileIndexBytes[3] = (byte) (fileIndex);
            System.arraycopy(fileIndexBytes, 0, bytes, 12, 4);
            return bytes;
        }

        public static String getBlockPath(CloudFileInterface baseFile,long block, int fileIndex,ManifestData manifestData){
            StringBuilder blockDirectory;
            //block directory is the left 52 bits of a long in hexadecimal string format zerofill to 2 characters min
            blockDirectory = new StringBuilder(Long.toHexString(block >> 12));
            while(blockDirectory.length() < 2) {
                blockDirectory.insert(0, "0");
            }

            //block filename is the right 12 bits of a long in hexadecimal string format zerofill to 3 characters min
            StringBuilder blockFileName;
            blockFileName = new StringBuilder(Long.toHexString(block & 0xFFF));
            while(blockFileName.length() < 3) {
                blockFileName.insert(0, "0");
            }

            if(fileIndex == 0) {
                return baseFile.getName() + "/" + blockDirectory +  "/" + blockFileName;
            } else {
                //System.out.println("File Index: " + fileIndex + " Block Number: " + block);
                return manifestData.sourceFiles.get(fileIndex - 1) + "/" + blockDirectory + "/" + blockFileName;
            }
        }
    }

    public String getHeader() {
        StringBuilder header = new StringBuilder();
        if(fileName != null) {
            header.append("fileName:").append(fileName).append("\n");
        }

        if(fileSize != null) {
            header.append("fileSize:").append(fileSize).append("\n");
        }

        header.append("blockSize:").append(blockSize).append("\n");
        header.append("version:").append(version).append("\n");
        if(sourceFiles != null && !sourceFiles.isEmpty()) {
            header.append("files:");
            for(String file : sourceFiles) {
                header.append(file).append(",");
            }
            header.deleteCharAt(header.length()-1);
            header.append("\n");
        }
        //data block is next
        header.append("\n");
        return header.toString();
    }
}
