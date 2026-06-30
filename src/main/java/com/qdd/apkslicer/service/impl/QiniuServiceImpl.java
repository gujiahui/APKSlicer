package com.qdd.apkslicer.service.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.springframework.stereotype.Service;

import com.qdd.apkslicer.config.QiniuConfig;
import com.qdd.apkslicer.entity.SystemSettings;
import com.qdd.apkslicer.service.QiniuService;
import com.qdd.apkslicer.service.SettingsService;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.persistent.FileRecorder;
import com.qiniu.util.Auth;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class QiniuServiceImpl implements QiniuService {

    private static final long MULTIPART_THRESHOLD = 100 * 1024 * 1024;

    private final QiniuConfig qiniuConfig;
    private final SettingsService settingsService;
    private final UploadManager uploadManager;
    private final Auth auth;
    private final BucketManager bucketManager;

    public QiniuServiceImpl(QiniuConfig qiniuConfig, SettingsService settingsService) {
        this.qiniuConfig = qiniuConfig;
        this.settingsService = settingsService;
        Configuration cfg = new Configuration(Region.region0());
        cfg.resumableUploadAPIVersion = Configuration.ResumableUploadAPIVersion.V2;
        this.uploadManager = new UploadManager(cfg);
        this.auth = Auth.create(qiniuConfig.getAccessKey(), qiniuConfig.getSecretKey());
        this.bucketManager = new BucketManager(auth, cfg);
    }

    @Override
    public String uploadFile(File file) {
        try {
            String key = "apk/" + UUID.randomUUID() + "_" + file.getName();
            String token = auth.uploadToken(qiniuConfig.getBucket());

            long fileSize = file.length();
            log.info("Uploading file: {}, size: {} bytes", file.getName(), fileSize);

            SystemSettings settings = settingsService.getSettings();
            long thresholdBytes = (long) settings.getBreakpointUploadThreshold() * 1024 * 1024;
            boolean useBreakpoint = settings.isBreakpointUploadEnabled() && fileSize > thresholdBytes;

            if (useBreakpoint) {
                return uploadMultipart(file, key, token);
            } else {
                return uploadNormal(file, key, token);
            }
        } catch (Exception e) {
            log.error("Failed to upload file to Qiniu", e);
            return null;
        }
    }

    @Override
    public String uploadFileWithKey(File file, String key) {
        return uploadFileWithKey(file, key, null);
    }

    @Override
    public String uploadFileWithKey(File file, String key, IntConsumer progressCallback) {
        try {
            String token = auth.uploadToken(qiniuConfig.getBucket());

            long fileSize = file.length();
            log.info("Uploading file with custom key: {}, key: {}, size: {} bytes", file.getName(), key, fileSize);

            if (progressCallback != null) {
                return uploadWithProgress(file, key, token, progressCallback);
            }

            SystemSettings settings = settingsService.getSettings();
            long thresholdBytes = (long) settings.getBreakpointUploadThreshold() * 1024 * 1024;
            boolean useBreakpoint = settings.isBreakpointUploadEnabled() && fileSize > thresholdBytes;

            if (useBreakpoint) {
                return uploadMultipart(file, key, token);
            } else {
                return uploadNormal(file, key, token);
            }
        } catch (Exception e) {
            log.error("Failed to upload file to Qiniu with key: {}", key, e);
            return null;
        }
    }

    private String uploadWithProgress(File file, String key, String token, IntConsumer progressCallback) throws IOException {
        progressCallback.accept(0);
        try (InputStream inputStream = new ProgressInputStream(new FileInputStream(file), file.length(), progressCallback)) {
            Response response = uploadManager.put(inputStream, file.length(), key, token, null, null, false);
            if (response.isOK()) {
                progressCallback.accept(100);
                String url = qiniuConfig.getDomain() + "/" + key;
                log.info("File uploaded to Qiniu with progress: {}", url);
                return url;
            } else {
                log.error("Qiniu upload failed: {}", response.toString());
                return null;
            }
        }
    }

    private String uploadNormal(File file, String key, String token) throws QiniuException {
        Response response = uploadManager.put(file.getAbsolutePath(), key, token);
        if (response.isOK()) {
            String url = qiniuConfig.getDomain() + "/" + key;
            log.info("File uploaded to Qiniu: {}", url);
            return url;
        } else {
            log.error("Qiniu upload failed: {}", response.toString());
            return null;
        }
    }

    private String uploadMultipart(File file, String key, String token) throws IOException {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "qiniu_upload");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            FileRecorder fileRecorder = new FileRecorder(tempDir.getAbsolutePath());

            com.qiniu.storage.UploadManager multipartUploadManager = new com.qiniu.storage.UploadManager(
                    new Configuration(Region.region0()), fileRecorder);

            Response response = multipartUploadManager.put(file.getAbsolutePath(), key, token);

            if (response.isOK()) {
                String url = qiniuConfig.getDomain() + "/" + key;
                log.info("Multipart file uploaded to Qiniu: {}", url);
                return url;
            } else {
                log.error("Qiniu multipart upload failed: {}", response.toString());
                return null;
            }
        } catch (QiniuException e) {
            log.error("Qiniu multipart upload exception", e);
            return null;
        }
    }

    @Override
    public String getFileDownloadUrl(String key) {
        return qiniuConfig.getDomain() + "/" + key;
    }

    @Override
    public boolean fileExists(String key) {
        try {
            com.qiniu.storage.model.FileInfo fileInfo = bucketManager.stat(qiniuConfig.getBucket(), key);
            return fileInfo != null;
        } catch (QiniuException e) {
            if (e.code() == 612) {
                return false;
            }
            log.error("CDN文件检查失败: key={}", key, e);
            throw new RuntimeException("CDN文件检查失败");
        }
    }

    private static class ProgressInputStream extends FilterInputStream {
        private final long totalBytes;
        private final IntConsumer progressCallback;
        private long bytesRead;
        private int lastProgress;

        ProgressInputStream(InputStream in, long totalBytes, IntConsumer progressCallback) {
            super(in);
            this.totalBytes = totalBytes;
            this.progressCallback = progressCallback;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                updateProgress(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = super.read(b, off, len);
            if (count > 0) {
                updateProgress(count);
            }
            return count;
        }

        private void updateProgress(int count) {
            bytesRead += count;
            if (totalBytes <= 0) {
                return;
            }
            int progress = (int) Math.min(99, (bytesRead * 100) / totalBytes);
            if (progress > lastProgress) {
                lastProgress = progress;
                progressCallback.accept(progress);
            }
        }
    }
}
