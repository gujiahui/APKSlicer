package com.qdd.apkslicer.util;

import java.io.File;

public class FileHelpUtils {
    
    public static boolean isExistFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        File file = new File(filePath);
        return file.exists() && file.isFile();
    }
    
    public static boolean isExistDir(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            return false;
        }
        File dir = new File(dirPath);
        return dir.exists() && dir.isDirectory();
    }
    
    public static boolean createDir(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            return false;
        }
        File dir = new File(dirPath);
        if (!dir.exists()) {
            return dir.mkdirs();
        }
        return true;
    }
    
    public static boolean deleteFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        File file = new File(filePath);
        return file.exists() && file.delete();
    }
    
    public static boolean deleteDir(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            return false;
        }
        File dir = new File(dirPath);
        if (!dir.exists()) {
            return true;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDir(file.getAbsolutePath());
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }
}
