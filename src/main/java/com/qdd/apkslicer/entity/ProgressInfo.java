package com.qdd.apkslicer.entity;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Data;

@Data
public class ProgressInfo implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String taskId;
    private int totalChannels;
    private int totalLevels;
    private int completedChannels;
    private int completedLevels;
    private String status;  // "downloading", "processing", "uploading", "completed", "failed"
    private String downloadMessage;
    private long startTime;
    private long endTime;
    private String completeMessage;
    
    private Map<String, ChannelProgress> channelProgressMap = new ConcurrentHashMap<>();
    
    @Data
    public static class ChannelProgress implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private String channel;
        private Map<String, LevelProgress> levelProgressMap = new ConcurrentHashMap<>();
        
        @Data
        public static class LevelProgress implements Serializable {
            private static final long serialVersionUID = 1L;
            
            private String level;
            private String status;  // "pending", "processing", "success", "failed"
            private String message;
            private String qiniuUrl;
            private int progress;
            private long timestamp;
        }
        
        public void updateLevel(String level, boolean success, String message, String qiniuUrl) {
            updateLevel(level, success ? "success" : "failed", message, qiniuUrl, 100);
        }

        public void updateLevel(String level, String status, String message, String qiniuUrl, int progress) {
            LevelProgress levelProgress = levelProgressMap.computeIfAbsent(level, k -> new LevelProgress());
            levelProgress.setLevel(level);
            levelProgress.setStatus(status);
            levelProgress.setMessage(message);
            if (qiniuUrl != null) {
                levelProgress.setQiniuUrl(qiniuUrl);
            }
            levelProgress.setProgress(Math.max(0, Math.min(100, progress)));
            levelProgress.setTimestamp(System.currentTimeMillis());
        }
    }
    
    public void updateChannelProgress(String channel, String level, boolean success, String message, String qiniuUrl) {
        ChannelProgress channelProgress = channelProgressMap.computeIfAbsent(channel, k -> new ChannelProgress());
        channelProgress.setChannel(channel);
        channelProgress.updateLevel(level, success, message, qiniuUrl);
    }

    public void updateChannelProgress(String channel, String level, String status, String message, String qiniuUrl, int progress) {
        ChannelProgress channelProgress = channelProgressMap.computeIfAbsent(channel, k -> new ChannelProgress());
        channelProgress.setChannel(channel);
        channelProgress.updateLevel(level, status, message, qiniuUrl, progress);
    }
    
    public int calculateOverallProgress() {
        if ("completed".equals(status) || "failed".equals(status)) {
            return 100;
        }
        if (totalChannels == 0 || totalLevels == 0) {
            return 0;
        }
        int totalTasks = totalChannels * totalLevels;
        int completedProgress = 0;
        for (ChannelProgress cp : channelProgressMap.values()) {
            for (ChannelProgress.LevelProgress lp : cp.getLevelProgressMap().values()) {
                if ("success".equals(lp.getStatus()) || "failed".equals(lp.getStatus())) {
                    completedProgress += 100;
                } else {
                    completedProgress += lp.getProgress();
                }
            }
        }
        return (int) (completedProgress / (double) totalTasks);
    }
    
    public int getSuccessCount() {
        int count = 0;
        for (ChannelProgress cp : channelProgressMap.values()) {
            for (ChannelProgress.LevelProgress lp : cp.getLevelProgressMap().values()) {
                if ("success".equals(lp.getStatus())) {
                    count++;
                }
            }
        }
        return count;
    }
    
    public int getFailedCount() {
        int count = 0;
        for (ChannelProgress cp : channelProgressMap.values()) {
            for (ChannelProgress.LevelProgress lp : cp.getLevelProgressMap().values()) {
                if ("failed".equals(lp.getStatus())) {
                    count++;
                }
            }
        }
        return count;
    }
}
