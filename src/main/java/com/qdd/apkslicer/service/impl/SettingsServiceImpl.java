package com.qdd.apkslicer.service.impl;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;

import com.qdd.apkslicer.util.FileHelpUtils;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.qdd.apkslicer.config.StorageConfig;
import com.qdd.apkslicer.entity.SystemSettings;
import com.qdd.apkslicer.service.SettingsService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SettingsServiceImpl implements SettingsService {

    private final StorageConfig storageConfig;
    private volatile SystemSettings currentSettings;
    private String settingsFilePath;
    private final ReentrantLock writeLock = new ReentrantLock();

    public SettingsServiceImpl(StorageConfig storageConfig) {
        this.storageConfig = storageConfig;
    }

    @PostConstruct
    public void init() {
        settingsFilePath = storageConfig.getTempDir() + File.separator + "settings.json";
        Path path = Paths.get(settingsFilePath);

        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                currentSettings = JSON.parseObject(json, SystemSettings.class);
                if (currentSettings == null) {
                    currentSettings = SystemSettings.defaults();
                }
                log.info("Settings loaded from file: {}", settingsFilePath);
            } catch (Exception e) {
                log.warn("Failed to parse settings file, using defaults: {}", e.getMessage());
                currentSettings = SystemSettings.defaults();
            }
        } else {
            currentSettings = SystemSettings.defaults();
            log.info("No settings file found, using defaults");
        }

        applyLogLevel(currentSettings.isDebugLogEnabled());
    }

    @Override
    public SystemSettings getSettings() {
        return deepCopy(currentSettings);
    }

    @Override
    public SystemSettings updateSettings(SystemSettings settings) {
        validateSettings(settings);

        writeLock.lock();
        try {
            Path targetPath = Paths.get(settingsFilePath);
            Path tempPath = Paths.get(settingsFilePath + ".tmp");

            FileHelpUtils.createDir(targetPath.getParent().toString());

            String json = JSON.toJSONString(settings, true);
            Files.writeString(tempPath, json);

            try {
                Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.deleteIfExists(targetPath);
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            currentSettings = settings;
            applyLogLevel(settings.isDebugLogEnabled());

            log.info("Settings updated and persisted");
            return deepCopy(settings);
        } catch (Exception e) {
            log.error("Failed to persist settings", e);
            throw new RuntimeException("设置保存失败，请检查系统权限", e);
        } finally {
            writeLock.unlock();
        }
    }

    private void validateSettings(SystemSettings settings) {
        if (settings.getBreakpointUploadThreshold() < 1 || settings.getBreakpointUploadThreshold() > 10240) {
            throw new IllegalArgumentException("断点上传文件阈值必须在1到10240之间");
        }
        if (settings.getBreakpointUploadMaxTasks() < 1 || settings.getBreakpointUploadMaxTasks() > 10) {
            throw new IllegalArgumentException("断点上传最大任务数必须在1到10之间");
        }
        if (settings.getBreakpointDownloadThreshold() < 1 || settings.getBreakpointDownloadThreshold() > 10240) {
            throw new IllegalArgumentException("断点下载文件阈值必须在1到10240之间");
        }
        if (settings.getBreakpointDownloadMaxTasks() < 1 || settings.getBreakpointDownloadMaxTasks() > 10) {
            throw new IllegalArgumentException("断点下载最大任务数必须在1到10之间");
        }
    }

    private void applyLogLevel(boolean debugEnabled) {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger logger = loggerContext.getLogger("com.qdd.apkslicer");
            if (debugEnabled) {
                logger.setLevel(Level.DEBUG);
            } else {
                logger.setLevel(Level.INFO);
            }
            log.info("Log level set to: {}", debugEnabled ? "DEBUG" : "INFO");
        } catch (Exception e) {
            log.error("Failed to apply log level", e);
        }
    }

    private SystemSettings deepCopy(SystemSettings original) {
        String json = JSON.toJSONString(original);
        return JSON.parseObject(json, SystemSettings.class);
    }
}
