package com.qdd.apkslicer.scheduler;

import java.io.File;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.qdd.apkslicer.entity.ChannelInfo;
import com.qdd.apkslicer.entity.PkgApkPathEntity;
import com.qdd.apkslicer.entity.SignatureVersionEntity;
import com.qdd.apkslicer.enums.ResultCodes;
import com.qdd.apkslicer.service.ApkChannelService;
import com.qdd.apkslicer.service.ProgressService;
import com.qdd.apkslicer.service.QiniuService;
import com.qdd.apkslicer.service.SettingsService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SliceSubTask {

    private final String taskId;
    private final String channel;
    private final String level;
    private final String fullChannelCode;
    private final int currentTask;
    private final int totalTasks;
    private final String localApkPath;
    private final String filePrefix;
    private final String cdnPath;
    private final String dateStr;
    private final ApkChannelService apkChannelService;
    private final QiniuService qiniuService;
    private final ProgressService progressService;
    private final SignatureVersionEntity signatureVersionEntity;
    private final SettingsService settingsService;

    public SliceSubTask(String taskId, String channel, String level, String fullChannelCode,
                        int currentTask, int totalTasks, String localApkPath,
                        String filePrefix, String cdnPath, String dateStr,
                        ApkChannelService apkChannelService, QiniuService qiniuService,
                        ProgressService progressService, SignatureVersionEntity signatureVersionEntity,
                        SettingsService settingsService) {
        this.taskId = taskId;
        this.channel = channel;
        this.level = level;
        this.fullChannelCode = fullChannelCode;
        this.currentTask = currentTask;
        this.totalTasks = totalTasks;
        this.localApkPath = localApkPath;
        this.filePrefix = filePrefix;
        this.cdnPath = cdnPath;
        this.dateStr = dateStr;
        this.apkChannelService = apkChannelService;
        this.qiniuService = qiniuService;
        this.progressService = progressService;
        this.signatureVersionEntity = signatureVersionEntity;
        this.settingsService = settingsService;
    }

    public ChannelInfo execute() {
        ChannelInfo info = new ChannelInfo();
        info.setChannelCode(fullChannelCode);
        String stepInfo = String.format("[%d/%d] %s", currentTask, totalTasks, fullChannelCode);

        try {
            progressService.updateChannelProgress(taskId, channel, level, "processing", stepInfo + " 开始分包", 10);
            String pkgResult = apkChannelService.appPackageVer_2(localApkPath, fullChannelCode, "", signatureVersionEntity);
            JSONObject pkgJson = JSON.parseObject(pkgResult);

            if (ResultCodes.SUCCESS_CODE.equals(pkgJson.getString("resultCode"))) {
                PkgApkPathEntity entity = pkgJson.getObject("pkgApkPathEntity", PkgApkPathEntity.class);
                File outputFile = new File(entity.getApkDownloadPath());
                String customKey = buildCustomKey();

                boolean overwriteEnabled = settingsService.getSettings().isOverwriteEnabled();
                if (!overwriteEnabled) {
                    try {
                        if (qiniuService.fileExists(customKey)) {
                            info.setSuccess(false);
                            info.setErrorMessage("CDN文件已存在，未启用覆盖选项");
                            progressService.updateChannelProgress(taskId, channel, level, "failed", stepInfo + " CDN文件已存在，未启用覆盖选项", 100);
                            return info;
                        }
                    } catch (RuntimeException e) {
                        info.setSuccess(false);
                        info.setErrorMessage("CDN文件检查失败");
                        progressService.updateChannelProgress(taskId, channel, level, "failed", stepInfo + " CDN文件检查失败", 100);
                        return info;
                    }
                }

                progressService.updateChannelProgress(taskId, channel, level, "uploading", stepInfo + " 分包完成，开始上传七牛云", 50);
                String qiniuUrl = qiniuService.uploadFileWithKey(outputFile, customKey, percent -> {
                    int itemProgress = 50 + (int) Math.floor(percent * 0.49);
                    progressService.updateUploadProgress(taskId, channel, level,
                            stepInfo + " 上传七牛云 " + percent + "%", itemProgress);
                });

                if (qiniuUrl != null) {
                    info.setQiniuUrl(qiniuUrl);
                    info.setSuccess(true);
                    progressService.updateUploadProgress(taskId, channel, level, true, qiniuUrl);
                } else {
                    info.setSuccess(false);
                    info.setErrorMessage("上传七牛云失败");
                    progressService.updateUploadProgress(taskId, channel, level, false, null);
                }
            } else {
                String errorMsg = pkgJson.getString("resultMsg");
                info.setSuccess(false);
                info.setErrorMessage(errorMsg);
                progressService.updateChannelProgress(taskId, channel, level, "failed", stepInfo + " 分包失败: " + errorMsg, 100);
            }
        } catch (Exception e) {
            info.setSuccess(false);
            info.setErrorMessage("处理失败: " + e.getMessage());
            log.error("Failed to process channel: {}, thread: {}", fullChannelCode, Thread.currentThread().getName(), e);
            progressService.updateChannelProgress(taskId, channel, level, "failed", stepInfo + " 处理失败: " + e.getMessage(), 100);
        }

        return info;
    }

    private String buildCustomKey() {
        String key = "";
        if (cdnPath != null && !cdnPath.isEmpty()) {
            key = cdnPath;
            if (!key.endsWith("/")) {
                key += "/";
            }
        }
        if (filePrefix != null && !filePrefix.isEmpty()) {
            key += filePrefix + "_" + fullChannelCode + ".apk";
        } else {
            key += "apk_" + dateStr + "_" + fullChannelCode + ".apk";
        }
        return key;
    }
}
