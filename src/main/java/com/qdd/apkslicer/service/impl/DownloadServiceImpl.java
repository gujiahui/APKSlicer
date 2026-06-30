package com.qdd.apkslicer.service.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.stereotype.Service;

import com.qdd.apkslicer.config.StorageConfig;
import com.qdd.apkslicer.entity.SystemSettings;
import com.qdd.apkslicer.service.DownloadService;
import com.qdd.apkslicer.service.SettingsService;
import com.qdd.apkslicer.util.FileHelpUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DownloadServiceImpl implements DownloadService {
    
    private final StorageConfig storageConfig;
    private final SettingsService settingsService;
    private final CloseableHttpClient httpClient;
    
    private static final int CONNECTION_TIMEOUT = 30000;
    private static final int SOCKET_TIMEOUT = 300000;
    private static final int CONNECTION_REQUEST_TIMEOUT = 10000;
    
    private static final long DOWNLOAD_TIMEOUT = 30 * 60 * 1000;
    
    private final Map<String, DownloadTask> downloadTasks = new ConcurrentHashMap<>();
    
    private static class DownloadTask {
        volatile String result;
        volatile Exception error;
        CountDownLatch latch;
        
        DownloadTask() {
            this.latch = new CountDownLatch(1);
        }
    }
    
    public DownloadServiceImpl(StorageConfig storageConfig, SettingsService settingsService) {
        this.storageConfig = storageConfig;
        this.settingsService = settingsService;
        this.httpClient = createHttpClient();
    }
    
    private CloseableHttpClient createHttpClient() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(20);
        connectionManager.setDefaultMaxPerRoute(10);
        
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECTION_TIMEOUT)
                .setSocketTimeout(SOCKET_TIMEOUT)
                .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT)
                .build();
        
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }
    
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 5000;
    
    private volatile String currentDownloadTaskId = null;
    private volatile String currentDownloadStatus = "";
    
    @Override
    public String downloadFile(String url, String fileName) {
        SystemSettings settings = settingsService.getSettings();
        boolean breakpointEnabled = settings.isBreakpointDownloadEnabled();
        long thresholdBytes = (long) settings.getBreakpointDownloadThreshold() * 1024 * 1024;

        String filePath = storageConfig.getTempDir() + File.separator + fileName;
        String taskKey = url;
        
        long expectedSize = getRemoteFileSize(url);
        File existingFile = new File(filePath);
        
        if (existingFile.exists() && existingFile.length() > 0) {
            if (expectedSize > 0 && existingFile.length() == expectedSize) {
                log.info("File already exists and complete, skipping download: {}", filePath);
                return filePath;
            } else if (expectedSize > 0 && existingFile.length() < expectedSize) {
                if (breakpointEnabled && expectedSize > thresholdBytes) {
                    log.info("Breakpoint download enabled, keeping incomplete file for resume (expected: {}, actual: {})",
                            expectedSize, existingFile.length());
                } else {
                    log.warn("File exists but incomplete (expected: {}, actual: {}), deleting and re-downloading",
                            expectedSize, existingFile.length());
                    safeDeleteFile(existingFile);
                }
            } else {
                log.info("File exists but cannot verify size, using existing file: {}", filePath);
                return filePath;
            }
        }
        
        DownloadTask task = downloadTasks.get(taskKey);
        if (task != null) {
            log.info("Waiting for existing download task to complete: {}", url);
            try {
                boolean completed = task.latch.await(DOWNLOAD_TIMEOUT, TimeUnit.MILLISECONDS);
                if (completed) {
                    if (task.error != null) {
                        log.error("Previous download task failed: {}", task.error.getMessage());
                        return null;
                    }
                    return task.result;
                } else {
                    log.error("Timeout waiting for download task: {}", url);
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for download: {}", url);
                return null;
            }
        }
        
        DownloadTask newTask = new DownloadTask();
        task = downloadTasks.putIfAbsent(taskKey, newTask);
        if (task != null) {
            log.info("Another thread started downloading, waiting: {}", url);
            try {
                boolean completed = task.latch.await(DOWNLOAD_TIMEOUT, TimeUnit.MILLISECONDS);
                if (completed) {
                    if (task.error != null) {
                        log.error("Previous download task failed: {}", task.error.getMessage());
                        return null;
                    }
                    return task.result;
                } else {
                    log.error("Timeout waiting for download task: {}", url);
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for download: {}", url);
                return null;
            }
        }
        
        try {
            boolean useBreakpoint = breakpointEnabled && expectedSize > thresholdBytes;
            String result = executeDownloadWithRetry(url, filePath, expectedSize, newTask, useBreakpoint);
            return result;
        } finally {
            newTask.latch.countDown();
            downloadTasks.remove(taskKey);
        }
    }
    
    private String executeDownloadWithRetry(String url, String filePath, long expectedSize, DownloadTask task, boolean useBreakpoint) {
        int retryCount = 0;
        Exception lastException = null;
        String fileName = filePath.substring(filePath.lastIndexOf(File.separator) + 1);
        
        while (retryCount < MAX_RETRY_COUNT) {
            try {
                String attemptInfo = retryCount > 0 ? "(重试 " + retryCount + ")" : "";
                log.info("Starting download attempt {}/{} {} from URL: {} (breakpoint: {})", 
                        retryCount + 1, MAX_RETRY_COUNT, attemptInfo, url, useBreakpoint);
                updateDownloadStatus(task != null ? task.toString() : url, "正在下载文件: " + fileName + attemptInfo);
                
                HttpGet httpGet = new HttpGet(url);
                File existingFile = new File(filePath);
                long existingSize = existingFile.exists() ? existingFile.length() : 0;

                if (useBreakpoint && existingSize > 0) {
                    httpGet.setHeader("Range", "bytes=" + existingSize + "-");
                    log.info("Resuming download from byte {} for file: {}", existingSize, fileName);
                }

                HttpResponse response = httpClient.execute(httpGet);
                HttpEntity entity = response.getEntity();
                
                if (entity != null) {
                    long contentLength = entity.getContentLength();
                    long totalExpected = useBreakpoint && existingSize > 0 ? existingSize + contentLength : contentLength;
                    log.info("Downloading from URL: {}, content length: {} bytes, breakpoint resume from: {} bytes", 
                            url, contentLength, existingSize);
                    
                    FileHelpUtils.createDir(storageConfig.getTempDir());
                    try (InputStream inputStream = entity.getContent();
                         FileOutputStream fos = new FileOutputStream(filePath, useBreakpoint && existingSize > 0)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalRead = existingSize;
                        long lastReportedProgress = 0;
                        
                        updateDownloadStatus(task != null ? task.toString() : url, 
                                String.format("正在下载: %s (%.2f MB)", fileName, totalExpected / (1024.0 * 1024.0)));
                        
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                            totalRead += bytesRead;
                            
                            if (totalRead - lastReportedProgress >= 1024 * 1024 * 50) {
                                lastReportedProgress = totalRead;
                                double progress = totalExpected > 0 ? (totalRead * 100.0 / totalExpected) : -1;
                                String progressStr = totalExpected > 0 ? 
                                        String.format("%.1f%%", progress) : 
                                        String.format("%.2f MB", totalRead / (1024.0 * 1024.0));
                                updateDownloadStatus(task != null ? task.toString() : url, 
                                        String.format("正在下载: %s - %s", fileName, progressStr));
                            }
                        }
                        log.info("Download completed. Total expected: {} bytes, Total received: {} bytes", 
                                totalExpected, totalRead);
                        
                        if (totalExpected > 0 && totalRead != totalExpected) {
                            throw new Exception("Download incomplete: expected " + totalExpected + " bytes, received " + totalRead + " bytes");
                        }
                    }
                    updateDownloadStatus(task != null ? task.toString() : url, "下载完成: " + fileName);
                    log.info("File downloaded successfully: {}", filePath);
                    
                    task.result = filePath;
                    return filePath;
                } else {
                    throw new Exception("Empty response entity");
                }
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                log.error("Download attempt {} failed: {}", retryCount, e.getMessage());
                
                if (!useBreakpoint) {
                    safeDeleteFile(new File(filePath));
                }
                
                if (retryCount < MAX_RETRY_COUNT) {
                    updateDownloadStatus(task != null ? task.toString() : url, 
                            "下载失败，等待重试... (" + retryCount + "/" + MAX_RETRY_COUNT + ")");
                    try {
                        log.info("Waiting {} ms before retry...", RETRY_DELAY_MS);
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        updateDownloadStatus(task != null ? task.toString() : url, "下载失败: " + fileName);
        task.error = lastException;
        log.error("All {} download attempts failed for URL: {}", MAX_RETRY_COUNT, url, lastException);
        return null;
    }
    
    private long getRemoteFileSize(String url) {
        try {
            HttpHead httpHead = new HttpHead(url);
            HttpResponse response = httpClient.execute(httpHead);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                return entity.getContentLength();
            }
        } catch (Exception e) {
            log.warn("Failed to get remote file size for URL: {}", url, e);
        }
        return -1;
    }
    
    private void safeDeleteFile(File file) {
        if (file != null && file.exists()) {
            try {
                if (file.delete()) {
                    log.info("Deleted incomplete file: {}", file.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn("Failed to delete file: {}", file.getAbsolutePath(), e);
            }
        }
    }
    
    public String getCurrentDownloadStatus() {
        return currentDownloadStatus;
    }
    
    private void updateDownloadStatus(String taskId, String status) {
        this.currentDownloadTaskId = taskId;
        this.currentDownloadStatus = status;
        log.info("Download status updated: {}", status);
    }
}
