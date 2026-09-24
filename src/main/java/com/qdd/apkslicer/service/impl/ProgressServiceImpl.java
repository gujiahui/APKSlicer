package com.qdd.apkslicer.service.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
    /**
     * 每任务写锁。多个 slice-worker 线程会并发修改同一个 task 的 ProgressInfo
     * （updateChannelProgress 内部的 computeIfAbsent），因此「修改 → 冻结快照 → 写文件 → 推 SSE」
     * 必须在同一把锁内串行，否则会写出损坏 JSON 或推送给前端不一致的进度快照。
     */
    private final Map<String, Object> taskLocks = new ConcurrentHashMap<>();
    private final StorageConfig storageConfig;
    private final SseProgressService sseProgressService;

    private static final long TASK_EXPIRE_TIME = 24 * 60 * 60 * 1000L;

    public ProgressServiceImpl(StorageConfig storageConfig, SseProgressService sseProgressService) {
        this.storageConfig = storageConfig;
        this.sseProgressService = sseProgressService;
    }

    private Object taskLock(String taskId) {
        return taskLocks.computeIfAbsent(taskId, k -> new Object());
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
        log.info("Progress task created: taskId={}, totalChannels={}, totalLevels={}", taskId, totalChannels, totalLevels);
        commit(taskId, null);
    }

    @Override
    public void updateDownload(String taskId, String message) {
        commit(taskId, () -> {
            ProgressInfo progressInfo = taskMap.get(taskId);
            if (progressInfo != null) {
                progressInfo.setDownloadMessage(message);
                if (message.contains("完成") || message.contains("completed") || message.contains("successfully")) {
                    progressInfo.setStatus("processing");
                }
            }
        });
    }

    @Override
    public void updateChannelProgress(String taskId, String channel, String level, boolean success, String message) {
        commit(taskId, () -> {
            ProgressInfo progressInfo = taskMap.get(taskId);
            if (progressInfo != null) {
                progressInfo.updateChannelProgress(channel, level, success, message, null);
                progressInfo.setStatus(success ? "uploading" : "processing");
            }
        });
    }

    @Override
    public void updateChannelProgress(String taskId, String channel, String level, String status, String message, int progress) {
        commit(taskId, () -> {
            ProgressInfo progressInfo = taskMap.get(taskId);
            if (progressInfo != null) {
                progressInfo.updateChannelProgress(channel, level, status, message, null, progress);
                if ("uploading".equals(status)) {
                    progressInfo.setStatus("uploading");
                } else if (!"success".equals(status) && !"failed".equals(status)) {
                    progressInfo.setStatus("processing");
                }
            }
        });
    }

    @Override
    public void updateUploadProgress(String taskId, String channel, String level, boolean success, String qiniuUrl) {
        commit(taskId, () -> {
            ProgressInfo progressInfo = taskMap.get(taskId);
            if (progressInfo != null) {
                progressInfo.updateChannelProgress(channel, level, success, success ? "上传成功" : "上传失败", qiniuUrl);
            }
        });
    }

    @Override
    public void updateUploadProgress(String taskId, String channel, String level, String message, int progress) {
        commit(taskId, () -> {
            ProgressInfo progressInfo = taskMap.get(taskId);
            if (progressInfo != null) {
                progressInfo.setStatus("uploading");
                progressInfo.updateChannelProgress(channel, level, "uploading", message, null, progress);
            }
        });
    }

    @Override
    public void completeTask(String taskId, boolean success, String message) {
        ProgressInfo progressInfo = taskMap.get(taskId);
        if (progressInfo != null) {
            log.info("[Progress] 完成任务: taskId={}, success={}, message={}", taskId, success, message);
            commit(taskId, () -> {
                progressInfo.setStatus(success ? "completed" : "failed");
                progressInfo.setCompleteMessage(message);
                progressInfo.setEndTime(System.currentTimeMillis());
                if (!success) {
                    markIncompleteChannelsAsFailed(progressInfo);
                }
            });
            sseProgressService.completeTask(taskId);
            taskLocks.remove(taskId);
            log.info("[Progress] 任务已完成并推送SSE: taskId={}", taskId);
        } else {
            log.warn("[Progress] 完成任务时未找到进度信息: taskId={}", taskId);
        }
    }

    /**
     * 在每任务锁内完成「修改 → 冻结 JSON 快照 → 释放锁 → 原子写文件 + 推 SSE」。
     *
     * <p>冻结动作（JSON.toJSONString）必须在锁内执行，这样序列化看到的是一致的状态；
     * 写文件与推 SSE 在锁外执行，避免把 IO / 网络耗时挡在进度锁上。
     */
    private void commit(String taskId, Runnable mutation) {
        ProgressInfo progressInfo = taskMap.get(taskId);
        if (progressInfo == null) {
            return;
        }
        Object lock = taskLock(taskId);
        String json;
        synchronized (lock) {
            if (mutation != null) {
                mutation.run();
            }
            json = JSON.toJSONString(buildSnapshot(taskId, progressInfo));
        }
        saveProgressToFile(taskId, json);
        try {
            sseProgressService.sendProgress(taskId, json);
        } catch (Exception e) {
            log.error("Failed to send SSE progress: taskId={}", taskId, e);
        }
    }

    private JSONObject buildSnapshot(String taskId, ProgressInfo progressInfo) {
        JSONObject data = new JSONObject();
        data.put("taskId", taskId);
        data.put("status", progressInfo.getStatus());
        data.put("downloadMessage", progressInfo.getDownloadMessage());
        data.put("channelProgressMap", progressInfo.getChannelProgressMap());
        data.put("overallProgress", progressInfo.calculateOverallProgress());
        data.put("successCount", progressInfo.getSuccessCount());
        data.put("failedCount", progressInfo.getFailedCount());
        data.put("completeMessage", progressInfo.getCompleteMessage());
        return data;
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
                taskLocks.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 原子写进度文件：先写 .tmp 临时文件，再原子 move 覆盖正式文件。
     * 避免并发写 / 进程重启读档时读到半截 JSON 导致解析失败。
     */
    private void saveProgressToFile(String taskId, String json) {
        try {
            String progressDir = storageConfig.getTempDir() + File.separator + "progress";
            FileHelpUtils.createDir(progressDir);
            Path filePath = Paths.get(progressDir, taskId + ".json");
            Path tmpPath = Paths.get(progressDir, taskId + ".json.tmp");
            Files.writeString(tmpPath, json);
            try {
                Files.move(tmpPath, filePath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 个别文件系统不支持原子移动，退化为普通覆盖（已是整文件替换，风险有限）
                Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
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
