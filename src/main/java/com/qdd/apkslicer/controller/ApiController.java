package com.qdd.apkslicer.controller;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson.JSONObject;
import com.qdd.apkslicer.config.ChannelConfig;
import com.qdd.apkslicer.config.StorageConfig;
import com.qdd.apkslicer.entity.ApkInfoEntity;
import com.qdd.apkslicer.entity.ChannelInfo;
import com.qdd.apkslicer.entity.ProgressInfo;
import com.qdd.apkslicer.entity.SystemSettings;
import com.qdd.apkslicer.enums.ResultCodes;
import com.qdd.apkslicer.scheduler.ConcurrentSliceScheduler;
import com.qdd.apkslicer.service.ApkChannelService;
import com.qdd.apkslicer.service.ApkInfoService;
import com.qdd.apkslicer.service.CheckSignatureVersionService;
import com.qdd.apkslicer.service.DownloadService;
import com.qdd.apkslicer.service.ProgressService;
import com.qdd.apkslicer.service.QiniuService;
import com.qdd.apkslicer.service.SettingsService;
import com.qdd.apkslicer.service.SseProgressService;
import com.qdd.apkslicer.util.FileHelpUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final long TIMEOUT = 30 * 60 * 1000L;

    private final DownloadService downloadService;
    private final ApkChannelService apkChannelService;
    private final QiniuService qiniuService;
    private final StorageConfig storageConfig;
    private final ApkInfoService apkInfoService;
    private final ChannelConfig channelConfig;
    private final ProgressService progressService;
    private final SseProgressService sseProgressService;
    private final SettingsService settingsService;
    private final CheckSignatureVersionService signatureVersionService;
    private final ExecutorService sliceExecutor = Executors.newCachedThreadPool();

    @Value("${cdn.domain:https://YOUR_CDN_DOMAIN}")
    private String cdnDomain;

    @Value("${cdn.path:/YOUR_CDN_PATH}")
    private String cdnPath;

    public ApiController(DownloadService downloadService, ApkChannelService apkChannelService,
                         QiniuService qiniuService, StorageConfig storageConfig,
                         ApkInfoService apkInfoService, ChannelConfig channelConfig,
                         ProgressService progressService, SseProgressService sseProgressService,
                         SettingsService settingsService, CheckSignatureVersionService signatureVersionService) {
        this.downloadService = downloadService;
        this.apkChannelService = apkChannelService;
        this.qiniuService = qiniuService;
        this.storageConfig = storageConfig;
        this.apkInfoService = apkInfoService;
        this.channelConfig = channelConfig;
        this.progressService = progressService;
        this.sseProgressService = sseProgressService;
        this.settingsService = settingsService;
        this.signatureVersionService = signatureVersionService;
    }

    @GetMapping("/settings")
    public ResponseEntity<JSONObject> getSettings() {
        JSONObject result = new JSONObject();
        try {
            SystemSettings settings = settingsService.getSettings();
            result.put("resultCode", ResultCodes.SUCCESS_CODE);
            result.put("resultMsg", ResultCodes.SUCCESS_MSG);
            result.put("settings", settings);
        } catch (Exception e) {
            log.error("Failed to get settings", e);
            result.put("resultCode", ResultCodes.ERROR_CODE);
            result.put("resultMsg", "设置加载失败，已使用默认设置");
            result.put("settings", SystemSettings.defaults());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/settings")
    public ResponseEntity<JSONObject> updateSettings(@RequestBody SystemSettings settings) {
        JSONObject result = new JSONObject();
        try {
            SystemSettings updated = settingsService.updateSettings(settings);
            result.put("resultCode", ResultCodes.SUCCESS_CODE);
            result.put("resultMsg", ResultCodes.SUCCESS_MSG);
            result.put("settings", updated);
        } catch (IllegalArgumentException e) {
            result.put("resultCode", ResultCodes.ERROR_CODE);
            result.put("resultMsg", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to save settings", e);
            result.put("resultCode", ResultCodes.ERROR_CODE);
            result.put("resultMsg", "设置保存失败，请检查系统权限");
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/channels")
    public ResponseEntity<JSONObject> getChannels() {
        JSONObject result = new JSONObject();

        List<String> selectedChannels = new ArrayList<>();
        List<String> configDefaults = channelConfig.getDefaults();
        if (configDefaults != null && !configDefaults.isEmpty()) {
            for (String channel : configDefaults) {
                String trimmedChannel = channel.trim();
                if (!trimmedChannel.isEmpty()) {
                    selectedChannels.add(trimmedChannel);
                }
            }
        } else {
            selectedChannels.add("channel_example_1");
            selectedChannels.add("channel_example_2");
            selectedChannels.add("channel_example_3");
            selectedChannels.add("channel_example_4");
            selectedChannels.add("channel_example_5");
            selectedChannels.add("channel_example_6");
        }

        List<String> options = new ArrayList<>();
        List<String> configOptions = channelConfig.getOptions();
        if (configOptions != null && !configOptions.isEmpty()) {
            for (String channel : configOptions) {
                String trimmedChannel = channel.trim();
                if (!trimmedChannel.isEmpty()) {
                    options.add(trimmedChannel);
                }
            }
        }

        result.put("resultCode", ResultCodes.SUCCESS_CODE);
        result.put("resultMsg", ResultCodes.SUCCESS_MSG);
        result.put("selectedChannels", selectedChannels);
        result.put("optionalChannels", options);
        result.put("levels", channelConfig.getLevels());
        result.put("levelDefaultChannels", channelConfig.getLevelDefaultChannels());
        result.put("cdnDomain", cdnDomain);
        result.put("cdnPath", cdnPath);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/progress")
    public ResponseEntity<JSONObject> getProgress(@RequestParam("taskId") String taskId) {
        JSONObject result = new JSONObject();

        ProgressInfo progressInfo = progressService.getProgress(taskId);
        if (progressInfo == null) {
            result.put("resultCode", ResultCodes.ERROR_CODE);
            result.put("resultMsg", "任务不存在或已过期");
            return ResponseEntity.ok(result);
        }

        result.put("resultCode", ResultCodes.SUCCESS_CODE);
        result.put("resultMsg", ResultCodes.SUCCESS_MSG);
        result.put("progress", progressInfo);
        result.put("overallProgress", progressInfo.calculateOverallProgress());
        result.put("successCount", progressInfo.getSuccessCount());
        result.put("failedCount", progressInfo.getFailedCount());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/download/status")
    public ResponseEntity<JSONObject> getDownloadStatus() {
        JSONObject result = new JSONObject();

        String status = downloadService.getCurrentDownloadStatus();
        result.put("resultCode", ResultCodes.SUCCESS_CODE);
        result.put("resultMsg", ResultCodes.SUCCESS_MSG);
        result.put("status", status != null ? status : "");

        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/progress/sse")
    public void streamProgress(@RequestParam("taskId") String taskId, HttpServletRequest request, HttpServletResponse response) {
        log.info("[SSE] 客户端请求SSE连接, taskId: {}", taskId);
        sseProgressService.createEmitter(taskId, request, response);
    }

    @PostMapping("/slice")
    public ResponseEntity<JSONObject> sliceApk(
            @RequestParam(value = "cdnUrl", required = false) String cdnUrl,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam("channels") List<String> channels,
            @RequestParam(value = "filePrefix", required = false) String filePrefix,
            @RequestParam(value = "levels", required = false) List<String> levels) {

        JSONObject result = new JSONObject();
        String taskId = UUID.randomUUID().toString();
        log.info("[Slice] 收到分包请求: taskId={}, channels={}, levels={}, hasCdnUrl={}, hasFile={}", 
                taskId, channels, levels, cdnUrl != null && !cdnUrl.isEmpty(), file != null && !file.isEmpty());
        String uploadedApkPath = null;

        if (channels == null || channels.isEmpty()) {
            result.put("resultCode", ResultCodes.ERROR_CODE);
            result.put("resultMsg", "请选择渠道");
            return ResponseEntity.ok(result);
        }

        if ((cdnUrl == null || cdnUrl.isEmpty()) && (file == null || file.isEmpty())) {
            result.put("resultCode", ResultCodes.ERROR_CODE);
            result.put("resultMsg", "请提供CDN链接或上传APK文件");
            return ResponseEntity.ok(result);
        }

        try {
            if (file != null && !file.isEmpty()) {
                String fileName = file.getOriginalFilename();
                String safeFileName = fileName != null ? fileName : "upload.apk";
                uploadedApkPath = storageConfig.getTempDir() + File.separator + taskId + "_" + safeFileName;
                FileHelpUtils.createDir(storageConfig.getTempDir());
                file.transferTo(new File(uploadedApkPath));
            }

            List<String> levelsToProcess = resolveLevels(levels);
            progressService.createTask(taskId, channels.size(), levelsToProcess.size());

            final String taskCdnUrl = cdnUrl;
            final String taskUploadedApkPath = uploadedApkPath;
            final String taskFilePrefix = filePrefix;
            sliceExecutor.submit(() -> runSliceTask(taskId, taskCdnUrl, taskUploadedApkPath, channels, taskFilePrefix, levelsToProcess));

            result.put("resultCode", ResultCodes.SUCCESS_CODE);
            result.put("resultMsg", "任务已开始");
            result.put("taskId", taskId);
            log.info("[Slice] 任务已提交: taskId={}", taskId);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("Failed to prepare APK slice task", e);
            result.put("resultCode", ResultCodes.ERROR_CODE);
            result.put("resultMsg", "文件处理失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    private void runSliceTask(String taskId, String cdnUrl, String uploadedApkPath, List<String> channels,
                              String filePrefix, List<String> levelsToProcess) {
        String localApkPath = uploadedApkPath;
        log.info("[SliceTask] 开始执行分包任务: taskId={}, channels={}", taskId, channels);

        try {
            if (cdnUrl != null && !cdnUrl.isEmpty()) {
                String fileName = cdnUrl.substring(cdnUrl.lastIndexOf("/") + 1);
                progressService.updateDownload(taskId, "正在下载APK文件: " + fileName);
                localApkPath = downloadService.downloadFile(cdnUrl, fileName);
                if (localApkPath == null) {
                    progressService.completeTask(taskId, false, ResultCodes.DOWNLOAD_ERROR_MSG);
                    return;
                }
                progressService.updateDownload(taskId, "APK下载完成: " + fileName);
            } else {
                progressService.updateDownload(taskId, "APK文件已上传，开始分包");
            }

            String dateStr = new SimpleDateFormat("yyyyMMdd").format(new Date());

            ConcurrentSliceScheduler scheduler = new ConcurrentSliceScheduler(
                    apkChannelService, qiniuService, progressService, settingsService, signatureVersionService, channelConfig
            );
            List<ChannelInfo> channelResults = scheduler.executeConcurrentSlice(
                    taskId, localApkPath, channels, levelsToProcess, filePrefix, cdnPath, dateStr
            );

            long successCount = channelResults.stream().filter(ChannelInfo::isSuccess).count();
            boolean allSuccess = successCount == channelResults.size();
            log.info("[SliceTask] 分包任务完成: taskId={}, success={}, total={}, allSuccess={}", 
                    taskId, successCount, channelResults.size(), allSuccess);
            progressService.completeTask(taskId, allSuccess, String.format("完成: %d/%d 成功", successCount, channelResults.size()));
        } catch (Exception e) {
            log.error("[SliceTask] 分包任务异常: taskId={}", taskId, e);
            progressService.completeTask(taskId, false, "任务失败: " + e.getMessage());
        } finally {
            cleanUp(localApkPath);
            cleanUpOutputDir();
        }
    }

    private List<String> resolveLevels(List<String> levels) {
        List<String> levelsToProcess = new ArrayList<>();
        levelsToProcess.add("");

        if (levels != null && !levels.isEmpty()) {
            List<String> allLevels = channelConfig.getLevels();
            if (allLevels != null && !allLevels.isEmpty()) {
                String maxLevel = levels.stream()
                        .max((l1, l2) -> allLevels.indexOf(l1) - allLevels.indexOf(l2))
                        .orElse(null);

                if (maxLevel != null) {
                    int maxIndex = allLevels.indexOf(maxLevel);
                    for (int i = 0; i <= maxIndex; i++) {
                        levelsToProcess.add(allLevels.get(i));
                    }
                }
            }
        }

        return levelsToProcess;
    }


    @PostMapping("/check-channel")
    public ResponseEntity<JSONObject> checkChannel(
            @RequestParam(value = "cdnUrl", required = false) String cdnUrl,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        JSONObject result = new JSONObject();
        String localApkPath = null;

        try {
            if (cdnUrl != null && !cdnUrl.isEmpty()) {
                String fileName = cdnUrl.substring(cdnUrl.lastIndexOf("/") + 1);
                localApkPath = downloadService.downloadFile(cdnUrl, fileName);
                if (localApkPath == null) {
                    result.put("resultCode", ResultCodes.DOWNLOAD_ERROR_CODE);
                    result.put("resultMsg", ResultCodes.DOWNLOAD_ERROR_MSG);
                    return ResponseEntity.ok(result);
                }
            } else if (file != null && !file.isEmpty()) {
                String fileName = file.getOriginalFilename();
                localApkPath = storageConfig.getTempDir() + File.separator + fileName;
                file.transferTo(new File(localApkPath));
            } else {
                result.put("resultCode", ResultCodes.ERROR_CODE);
                result.put("resultMsg", "请提供CDN链接或上传APK文件");
                return ResponseEntity.ok(result);
            }

            ApkInfoEntity apkInfo = apkInfoService.getApkInfo(localApkPath);

            if (apkInfo.getErrorMessage() != null && !apkInfo.getErrorMessage().isEmpty()) {
                result.put("resultCode", ResultCodes.PARSE_ERROR_CODE);
                result.put("resultMsg", apkInfo.getErrorMessage());
                return ResponseEntity.ok(result);
            }

            result.put("resultCode", ResultCodes.SUCCESS_CODE);
            result.put("resultMsg", ResultCodes.SUCCESS_MSG);
            result.put("packageName", apkInfo.getPackageName() != null ? apkInfo.getPackageName() : "");
            result.put("versionName", apkInfo.getVersionName() != null ? apkInfo.getVersionName() : "");
            result.put("versionCode", apkInfo.getVersionCode() != null ? apkInfo.getVersionCode() : 0);
            result.put("keystoreMd5", apkInfo.getKeystoreMd5() != null ? apkInfo.getKeystoreMd5() : "");
            result.put("minSdkVersion", apkInfo.getMinSdkVersion() != null ? apkInfo.getMinSdkVersion() : 0);
            result.put("targetSdkVersion", apkInfo.getTargetSdkVersion() != null ? apkInfo.getTargetSdkVersion() : 0);
            result.put("isV1OK", apkInfo.getIsV1OK() != null ? apkInfo.getIsV1OK() : false);
            result.put("isV2", apkInfo.getIsV2() != null ? apkInfo.getIsV2() : false);
            result.put("isV2OK", apkInfo.getIsV2OK() != null ? apkInfo.getIsV2OK() : false);
            result.put("isV3", apkInfo.getIsV3() != null ? apkInfo.getIsV3() : false);
            result.put("isV3OK", apkInfo.getIsV3OK() != null ? apkInfo.getIsV3OK() : false);
            result.put("signatureDetail", apkInfo.getSignatureDetail() != null ? apkInfo.getSignatureDetail() : "");
            result.put("channelCode", apkInfo.getChannelCode() != null ? apkInfo.getChannelCode() : "未检测到渠道号");
        } catch (Exception e) {
            log.error("Failed to check channel", e);
            result.put("resultCode", ResultCodes.PARSE_ERROR_CODE);
            result.put("resultMsg", "检测失败: " + e.getMessage());
        } finally {
            cleanUp(localApkPath);
        }

        return ResponseEntity.ok(result);
    }

    private void cleanUp(String filePath) {
        if (filePath != null) {
            FileHelpUtils.deleteFile(filePath);
        }
    }

    private void cleanUpOutputDir() {
        FileHelpUtils.deleteDir(storageConfig.getOutputDir());
        new File(storageConfig.getOutputDir()).mkdirs();
    }
}
