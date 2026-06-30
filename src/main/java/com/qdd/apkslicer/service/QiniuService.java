package com.qdd.apkslicer.service;

import java.io.File;
import java.util.function.IntConsumer;

public interface QiniuService {
    String uploadFile(File file);
    String getFileDownloadUrl(String key);
    String uploadFileWithKey(File file, String key);
    String uploadFileWithKey(File file, String key, IntConsumer progressCallback);
    boolean fileExists(String key);
}
