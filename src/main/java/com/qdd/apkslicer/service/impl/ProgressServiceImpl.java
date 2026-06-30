package com.qdd.apkslicer.service.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.qdd.apkslicer.config.StorageConfig;
import com.qdd.apkslicer.entity.ProgressInfo;
import com.qdd.apkslicer.service.ProgressService;
import com.qdd.apkslicer.service.SseProgressService;
import com.qdd.apkslicer.util.FileHelpUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ProgressServiceImpl implements ProgressService {

    private final Map<String, ProgressInfo> taskMap = new ConcurrentHashMap<>();
    private final StorageConfig storageConfig;
    private final SseProgressService sseProgressService;

    private static final long TASK_EXPIRE_TIME = 24 * 60 * 60 * 1000L;

    public ProgressServiceImpl(StorageConfig storageConfig, SseProgressService sseProgressService) {
        this.storageConfig = storageConfig;
        this.sseProgressService = sseProgressService;
    }

    @Override
    public void createTask(String taskId, int totalChannels, int totalLevels) {
        ProgressInfo progressInfo = new ProgressInfo();
        progressInfo.setTaskId(taskId);
        progressInfo.setTotalChannels(totalChannels);
        progressInfo.setTotalLevels(totalLevels);
        progressInfo.setStatus("downloading");
        progressInfo.setStartTime(System.currentTimeMillis());
        taskMap.put(taskId, progressInfo);
        saveProgressToFile(taskId, progressInfo);
        sendSseProgress(taskId, progressInfo);
        log.info("Progress task created: taskId={}, totalChannels={}, totalLevels={}", taskId, totalChannels, totalLevels);
    }

    @Override
    public void updateDownload(String taskId, String message) {
        ProgressInfo progressInfo = taskMap.get(taskId);
        if (progressInfo != null) {
            progressInfo.setDownloadMessage(message);
            if (message.contains("完成") || message.contains("completed") || message.contains("successfully")) {
                progressInfo.setStatus("processing");
            }
            saveProgressToFile(taskId, progressInfo);
            sendSseProgress(taskId, progressInfo);
        }
    }

    @Override
    public void updateChannelProgress(String taskId, String channel, String level, boolean success, String message) {
        ProgressInfo progressInfo = taskMap.get(taskId);
        if (progressInfo != null) {
            progressInfo.updateChannelProgress(channel, level, success, message, null);
            progressInfo.setStatus(success ? "uploading" : "processing");
            saveProgressToFile(taskId, progressInfo);
            sendSseProgress(taskId, progressInfo);
        }
    }

    @Override
    public void updateChannelProgress(String taskId, String channel, String level, String status, String message, int progress) {
        ProgressInfo progressInfo = taskMap.get(taskId);
        if (progressInfo != null) {
            progressInfo.updateChannelProgress(channel, level, status, message, null, progress);
            if ("uploading".equals(status)) {
                progressInfo.setStatus("uploading");
            } else if (!"success".equals(status) && !"failed".equals(status)) {
                progressInfo.setStatus("processing");
            }
            saveProgressToFile(taskId, progressInfo);
            sendSseProgress(taskId, progressInfo);
        }
    }

    @Override
    public void updateUploadProgress(String taskId, String channel, String level, boolean success, String qiniuUrl) {
        ProgressInfo progressInfo = taskMap.get(taskId);
        if (progressInfo != null) {
            progressInfo.updateChannelProgress(channel, level, success, success ? "上传成功" : "上传失败", qiniuUrl);
            saveProgressToFile(taskId, progressInfo);
            sendSseProgress(taskId, progressInfo);
        }
    }

    @Override
    public void updateUploadProgress(String taskId, String channel, String level, String message, int progress) {
        ProgressInfo progressInfo = taskMap.get(taskId);
        if (progressInfo != null) {
            progressInfo.setStatus("uploading");
            progressInfo.updateChannelProgress(channel, level, "uploading", message, null, progress);
            saveProgressToFile(taskId, progressInfo);
            sendSseProgress(taskId, progressInfo);
        }
    }

    @Override
    public void completeTask(String taskId, boolean success, String message) {
        ProgressInfo progressInfo = taskMap.get(taskId);
        if (progressInfo != null) {
            log.info("[Progress] 完成任务: taskId={}, success={}, message={}", taskId, success, message);
            progressInfo.setStatus(success ? "completed" : "failed");
            progressInfo.setCompleteMessage(message);
            progressInfo.setEndTime(System.currentTimeMillis());
            if (!success) {
                markIncompleteChannelsAsFailed(progressInfo);
            }
            saveProgressToFile(taskId, progressInfo);
            sendSseProgress(taskId, progressInfo);
            sseProgressService.completeTask(taskId);
            log.info("[Progress] 任务已完成并推送SSE: taskId={}", taskId);
        } else {
            log.warn("[Progress] 完成任务时未找到进度信息: taskId={}", taskId);
        }
    }

    private void markIncompleteChannelsAsFailed(ProgressInfo progressInfo) {
        int marked = 0;
        for (ProgressInfo.ChannelProgress cp : progressInfo.getChannelProgressMap().values()) {
            for (ProgressInfo.ChannelProgress.LevelProgress lp : cp.getLevelProgressMap().values()) {
                if (!"success".equals(lp.getStatus()) && !"failed".equals(lp.getStatus())) {
                    lp.setStatus("failed");
                    lp.setProgress(100);
                    lp.setMessage(lp.getMessage() != null ? lp.getMessage() : "任务已终止");
                    lp.setTimestamp(System.currentTimeMillis());
                    marked++;
                }
            }
        }
        log.info("[Progress] 标记未完成渠道为failed: count={}", marked);
    }

    private void sendSseProgress(String taskId, ProgressInfo progressInfo) {
        try {
            JSONObject progressData = new JSONObject();
            progressData.put("taskId", taskId);
            progressData.put("status", progressInfo.getStatus());
            progressData.put("downloadMessage", progressInfo.getDownloadMessage());
            progressData.put("channelProgressMap", progressInfo.getChannelProgressMap());
            progressData.put("overallProgress", progressInfo.calculateOverallProgress());
            progressData.put("successCount", progressInfo.getSuccessCount());
            progressData.put("failedCount", progressInfo.getFailedCount());
            progressData.put("completeMessage", progressInfo.getCompleteMessage());

            sseProgressService.sendProgress(taskId, progressData);
        } catch (Exception e) {
            log.error("Failed to send SSE progress: taskId={}", taskId, e);
        }
    }

    @Override
    public ProgressInfo getProgress(String taskId) {
        ProgressInfo progressInfo = taskMap.get(taskId);
        if (progressInfo != null) {
            return progressInfo;
        }

        progressInfo = loadProgressFromFile(taskId);
        if (progressInfo != null) {
            taskMap.put(taskId, progressInfo);
        }

        return progressInfo;
    }

    @Override
    public void cleanExpiredTasks() {
        long currentTime = System.currentTimeMillis();
        taskMap.entrySet().removeIf(entry -> {
            ProgressInfo info = entry.getValue();
            if (info.getEndTime() > 0 && (currentTime - info.getEndTime()) > TASK_EXPIRE_TIME) {
                deleteProgressFile(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void saveProgressToFile(String taskId, ProgressInfo progressInfo) {
        try {
            String progressDir = storageConfig.getTempDir() + File.separator + "progress";
            FileHelpUtils.createDir(progressDir);
            Path filePath = Paths.get(progressDir, taskId + ".json");
            String json = JSON.toJSONString(progressInfo);
            Files.writeString(filePath, json);
        } catch (IOException e) {
            log.error("Failed to save progress file: taskId={}", taskId, e);
        }
    }

    private ProgressInfo loadProgressFromFile(String taskId) {
        try {
            String progressDir = storageConfig.getTempDir() + File.separator + "progress";
            Path filePath = Paths.get(progressDir, taskId + ".json");
            if (Files.exists(filePath)) {
                String json = Files.readString(filePath);
                return JSON.parseObject(json, ProgressInfo.class);
            }
        } catch (IOException e) {
            log.error("Failed to load progress file: taskId={}", taskId, e);
        }
        return null;
    }

    private void deleteProgressFile(String taskId) {
        try {
            String progressDir = storageConfig.getTempDir() + File.separator + "progress";
            Path filePath = Paths.get(progressDir, taskId + ".json");
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("Failed to delete progress file: taskId={}", taskId, e);
        }
    }
}
