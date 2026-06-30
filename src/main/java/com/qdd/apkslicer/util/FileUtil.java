package com.qdd.apkslicer.util;

import java.io.*;

public class FileUtil {
    
    public static void copyFile(File source, File target) throws IOException {
        try (InputStream is = new FileInputStream(source);
             OutputStream os = new FileOutputStream(target)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
        }
    }
    
    public static void del(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }
}
