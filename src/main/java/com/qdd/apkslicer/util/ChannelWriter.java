package com.qdd.apkslicer.util;

import java.io.*;

public class ChannelWriter {
    
    public static void put(File target, String channelCode) {
        try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
            raf.seek(raf.length());
            raf.write(channelCode.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
