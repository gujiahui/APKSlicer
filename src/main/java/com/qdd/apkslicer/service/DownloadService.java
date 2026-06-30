package com.qdd.apkslicer.service;

public interface DownloadService {
    String downloadFile(String url, String fileName);
    
    String getCurrentDownloadStatus();
}
