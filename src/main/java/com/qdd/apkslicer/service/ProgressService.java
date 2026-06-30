package com.qdd.apkslicer.service;

import com.qdd.apkslicer.entity.ProgressInfo;

public interface ProgressService {
    
    /**
     * 创建新的进度任务
     * @param taskId 任务ID
     * @param totalChannels 总渠道数
     * @param totalLevels 层级数（层级选项数量）
     */
    void createTask(String taskId, int totalChannels, int totalLevels);
    
    /**
     * 更新当前下载进度
     * @param taskId 任务ID
     * @param message 下载信息
     */
    void updateDownload(String taskId, String message);
    
    /**
     * 更新分包进度
     * @param taskId 任务ID
     * @param channel 渠道号
     * @param level 层级后缀
     * @param success 是否成功
     * @param message 状态信息
     */
    void updateChannelProgress(String taskId, String channel, String level, boolean success, String message);

    void updateChannelProgress(String taskId, String channel, String level, String status, String message, int progress);
    
    /**
     * 更新上传进度
     * @param taskId 任务ID
     * @param channel 渠道号
     * @param level 层级后缀
     * @param success 是否成功
     * @param qiniuUrl 七牛云URL
     */
    void updateUploadProgress(String taskId, String channel, String level, boolean success, String qiniuUrl);

    void updateUploadProgress(String taskId, String channel, String level, String message, int progress);
    
    /**
     * 标记任务完成
     * @param taskId 任务ID
     * @param success 是否全部成功
     * @param message 完成信息
     */
    void completeTask(String taskId, boolean success, String message);
    
    /**
     * 获取任务进度
     * @param taskId 任务ID
     * @return 进度信息
     */
    ProgressInfo getProgress(String taskId);
    
    /**
     * 清除过期任务
     */
    void cleanExpiredTasks();
}
