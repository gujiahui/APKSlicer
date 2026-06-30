package com.qdd.apkslicer.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.qdd.apkslicer.config.ChannelConfig;
import com.qdd.apkslicer.entity.ChannelInfo;
import com.qdd.apkslicer.entity.SignatureVersionEntity;
import com.qdd.apkslicer.service.ApkChannelService;
import com.qdd.apkslicer.service.CheckSignatureVersionService;
import com.qdd.apkslicer.service.ProgressService;
import com.qdd.apkslicer.service.QiniuService;
import com.qdd.apkslicer.service.SettingsService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConcurrentSliceScheduler {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    private final ApkChannelService apkChannelService;
    private final QiniuService qiniuService;
    private final ProgressService progressService;
    private final SettingsService settingsService;
    private final CheckSignatureVersionService signatureVersionService;
    private final ChannelConfig channelConfig;

    public ConcurrentSliceScheduler(ApkChannelService apkChannelService,
                                    QiniuService qiniuService,
                                    ProgressService progressService,
                                    SettingsService settingsService,
                                    CheckSignatureVersionService signatureVersionService,
                                    ChannelConfig channelConfig) {
        this.apkChannelService = apkChannelService;
        this.qiniuService = qiniuService;
        this.progressService = progressService;
        this.settingsService = settingsService;
        this.signatureVersionService = signatureVersionService;
        this.channelConfig = channelConfig;
    }

    public List<ChannelInfo> executeConcurrentSlice(String taskId, String localApkPath,
                                                     List<String> channels, List<String> levelsToProcess,
                                                     String filePrefix, String cdnPath, String dateStr) {
        int maxTasks = readConcurrency();
        List<String> levelDefaultChannels = channelConfig.getLevelDefaultChannels();
        int totalTasks = calculateTotalTasks(channels, levelsToProcess, levelDefaultChannels);
        log.info("Starting concurrent slice task: taskId={}, channels={}, levels={}, totalSubTasks={}, concurrency={}",
                taskId, channels.size(), levelsToProcess.size(), totalTasks, maxTasks);

        SignatureVersionEntity signatureVer = preQuerySignature(taskId, localApkPath);
        if (signatureVer == null) {
            return Collections.emptyList();
        }

        List<SliceSubTask> subTasks = buildSubTasks(taskId, localApkPath, channels, levelsToProcess,
                filePrefix, cdnPath, dateStr, totalTasks, signatureVer, levelDefaultChannels);

        ThreadPoolExecutor executor = createExecutor(maxTasks);
        List<Future<ChannelInfo>> futures = submitTasks(executor, subTasks);

        List<ChannelInfo> results = awaitResults(futures);

        executor.shutdown();
        log.info("Concurrent slice task completed: taskId={}, results={}", taskId, results.size());

        return results;
    }

    private int calculateTotalTasks(List<String> channels, List<String> levelsToProcess, List<String> levelDefaultChannels) {
        int count = 0;
        for (String channel : channels) {
            if (levelDefaultChannels != null && levelDefaultChannels.contains(channel) && levelsToProcess.size() > 1) {
                count += levelsToProcess.size();
            } else {
                count += 1;
            }
        }
        return count;
    }

    private SignatureVersionEntity preQuerySignature(String taskId, String localApkPath) {
        try {
            SignatureVersionEntity signatureVer = signatureVersionService.getSignatureVer(localApkPath, true);
            if (signatureVer == null) {
                log.error("签名查询结果为空: taskId={}, apkPath={}", taskId, localApkPath);
                return null;
            }
            log.info("签名预查询完成: taskId={}, apkPath={}, V1={}, V2={}, V3={}",
                    taskId, localApkPath, signatureVer.getIsV1OK(), signatureVer.getIsV2OK(), signatureVer.getIsV3OK());
            if (!signatureVer.getIsV1OK() && !signatureVer.getIsV2OK() && !signatureVer.getIsV3OK()) {
                log.error("无法检测到APK签名信息: taskId={}, apkPath={}", taskId, localApkPath);
                return null;
            }
            return signatureVer;
        } catch (Exception e) {
            log.error("签名预查询异常: taskId={}, apkPath={}", taskId, localApkPath, e);
            return null;
        }
    }

    private int readConcurrency() {
        try {
            int maxTasks = settingsService.getSettings().getBreakpointUploadMaxTasks();
            if (maxTasks < 1 || maxTasks > 10) {
                log.warn("Invalid concurrency value: {}, using default 1", maxTasks);
                return 1;
            }
            return maxTasks;
        } catch (Exception e) {
            log.warn("Failed to read concurrency setting, using default 1", e);
            return 1;
        }
    }

    private List<SliceSubTask> buildSubTasks(String taskId, String localApkPath,
                                              List<String> channels, List<String> levelsToProcess,
                                              String filePrefix, String cdnPath, String dateStr,
                                              int totalTasks, SignatureVersionEntity signatureVer,
                                              List<String> levelDefaultChannels) {
        List<SliceSubTask> subTasks = new ArrayList<>();
        int currentTask = 1;
        for (String channel : channels) {
            boolean isLevelChannel = levelDefaultChannels != null && levelDefaultChannels.contains(channel);
            if (isLevelChannel && levelsToProcess.size() > 1) {
                for (String level : levelsToProcess) {
                    subTasks.add(new SliceSubTask(
                            taskId, channel, level, channel + level,
                            currentTask++, totalTasks, localApkPath,
                            filePrefix, cdnPath, dateStr,
                            apkChannelService, qiniuService, progressService, signatureVer, settingsService
                    ));
                }
            } else {
                subTasks.add(new SliceSubTask(
                        taskId, channel, "", channel,
                        currentTask++, totalTasks, localApkPath,
                        filePrefix, cdnPath, dateStr,
                        apkChannelService, qiniuService, progressService, signatureVer, settingsService
                ));
            }
        }
        return subTasks;
    }

    private ThreadPoolExecutor createExecutor(int maxTasks) {
        return new ThreadPoolExecutor(
                maxTasks, maxTasks,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "slice-worker-" + THREAD_COUNTER.incrementAndGet());
                    t.setDaemon(false);
                    return t;
                }
        );
    }

    private List<Future<ChannelInfo>> submitTasks(ThreadPoolExecutor executor, List<SliceSubTask> subTasks) {
        List<Future<ChannelInfo>> futures = new ArrayList<>();
        for (SliceSubTask subTask : subTasks) {
            futures.add(executor.submit(subTask::execute));
        }
        return futures;
    }

    private List<ChannelInfo> awaitResults(List<Future<ChannelInfo>> futures) {
        List<ChannelInfo> results = new ArrayList<>();
        for (Future<ChannelInfo> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                log.error("Sub-task execution failed", e);
                ChannelInfo failInfo = new ChannelInfo();
                failInfo.setSuccess(false);
                failInfo.setErrorMessage("子任务执行异常: " + e.getMessage());
                results.add(failInfo);
            }
        }
        return results;
    }
}
