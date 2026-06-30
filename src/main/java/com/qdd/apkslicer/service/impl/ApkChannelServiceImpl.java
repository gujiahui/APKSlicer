package com.qdd.apkslicer.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.meituan.android.walle.ChannelWriter;
import com.qdd.apkslicer.config.StorageConfig;
import com.qdd.apkslicer.entity.PkgApkPathEntity;
import com.qdd.apkslicer.entity.SignatureVersionEntity;
import com.qdd.apkslicer.enums.ResultCodes;
import com.qdd.apkslicer.service.ApkChannelService;
import com.qdd.apkslicer.service.CheckSignatureVersionService;
import com.qdd.apkslicer.util.FileHelpUtils;
import com.qdd.apkslicer.util.MCPTool;
import com.qdd.apkslicer.util.ReadPropertity;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ApkChannelServiceImpl implements ApkChannelService {

    private static final String APK = "apk";

    private final StorageConfig storageConfig;
    private final CheckSignatureVersionService signatureVersionService;

    @Value("${app.walle-jar-path:${user.dir}/src/main/resources/lib/walle-cli-all.jar}")
    private String walleJarPath;

    public ApkChannelServiceImpl(StorageConfig storageConfig,
                                 CheckSignatureVersionService signatureVersionService) {
        this.storageConfig = storageConfig;
        this.signatureVersionService = signatureVersionService;
    }

    @Override
    public String appPackageVer_2(String apkLocalPath, String channelCode, String password) {
        log.info("------------------------打包渠道号,开始--------------------------");
        log.info("appPackageVer_2-apkLocalPath:" + apkLocalPath);
        long time1 = System.currentTimeMillis();
        JSONObject json = new JSONObject();
        PkgApkPathEntity pkgApkPathEntity = new PkgApkPathEntity();

        File sourceFile = new File(apkLocalPath);
        if (!sourceFile.exists()) {
            log.error("源APK文件不存在: {}", apkLocalPath);
            json.put("resultCode", ResultCodes.FILE_NOT_FOUND_CODE);
            json.put("resultMsg", "源APK文件不存在: " + apkLocalPath);
            return json.toString();
        }

        String outPath = storageConfig.getOutputDir() + File.separator + APK + File.separator + channelCode + File.separator;
        log.info("appPackageVer_2-outPath:" + outPath);

        File outdir = new File(outPath);
        if (!outdir.exists()) {
            outdir.mkdirs();
        }

        String fileName = sourceFile.getName();
        File target = new File(outdir, fileName);

        try {
            // 先检查APK文件是否有严重格式错误
            String checkResult = checkApkFormat(sourceFile);
            if (checkResult != null) {
                log.error("APK文件格式错误: {}", checkResult);
                json.put("resultCode", ResultCodes.ERROR_CODE);
                json.put("resultMsg", "APK文件格式错误: " + checkResult);
                return json.toString();
            }

            SignatureVersionEntity signatureVer = signatureVersionService.getSignatureVer(sourceFile.getAbsolutePath(), true);
            log.info("签名检测结果: V1={}, V2={},V3={}", signatureVer.getIsV1OK(), signatureVer.getIsV2OK(), signatureVer.getIsV3OK());

            boolean success = false;

            if (signatureVer.getIsV2OK() || signatureVer.getIsV1OK() || signatureVer.getIsV3OK()) {
//                success = channelPackageWithWalle(sourceFile, target, channelCode);
                success = channelPackageWithSignature(sourceFile, target, channelCode,signatureVer);
                if (success) {
                    log.info("Walle打渠道号包成功---------------");
                }
            } else {
                log.error("无法检测到APK签名信息---------------");
                json.put("resultCode", ResultCodes.ERROR_CODE);
                json.put("resultMsg", "无法检测到APK签名信息");
                return json.toString();
            }

            if (!success) {
                log.error("Walle分包失败---------------");
                json.put("resultCode", ResultCodes.ERROR_CODE);
                json.put("resultMsg", "Walle分包失败");
                return json.toString();
            }

            json.put("resultCode", ResultCodes.SUCCESS_CODE);
            json.put("resultMsg", ResultCodes.SUCCESS_MSG);

        } catch (Exception e) {
            log.error("apk打包渠道号失败", e);
            json.put("resultCode", ResultCodes.ERROR_CODE);
            json.put("resultMsg", "打包失败: " + e.getMessage());
            return json.toString();
        }

        String downloadUrl = ReadPropertity.getProperty("DOWNLOAD_URL");
        pkgApkPathEntity.setApkDownloadUrl(downloadUrl + APK + File.separator + channelCode + File.separator + target.getName());
        pkgApkPathEntity.setApkDownloadPath(outPath + target.getName());
        pkgApkPathEntity.setChannelCode(channelCode);

        long time2 = System.currentTimeMillis();
        log.info("单渠道打包耗时:" + (time2 - time1) + "ms");
        log.info("------------------------打包方式打包渠道号,完成--------------------------");

        if (FileHelpUtils.isExistFile(outPath + target.getName())) {
            json.put("resultCode", ResultCodes.SUCCESS_CODE);
            json.put("resultMsg", ResultCodes.SUCCESS_MSG);
            json.put("pkgApkPathEntity", pkgApkPathEntity);
        } else {
            json.put("resultCode", ResultCodes.ERROR_CODE);
            json.put("resultMsg", ResultCodes.ERROR_MSG);
        }

        return json.toString();
    }

    @Override
    public String appPackageVer_2(String apkLocalPath, String channelCode, String password, SignatureVersionEntity signatureVer) {
        log.info("------------------------打包渠道号(预查询签名),开始--------------------------");
        log.info("appPackageVer_2-apkLocalPath:" + apkLocalPath);
        long time1 = System.currentTimeMillis();
        JSONObject json = new JSONObject();
        PkgApkPathEntity pkgApkPathEntity = new PkgApkPathEntity();

        File sourceFile = new File(apkLocalPath);
        if (!sourceFile.exists()) {
            log.error("源APK文件不存在: {}", apkLocalPath);
            json.put("resultCode", ResultCodes.FILE_NOT_FOUND_CODE);
            json.put("resultMsg", "源APK文件不存在: " + apkLocalPath);
            return json.toString();
        }

        String outPath = storageConfig.getOutputDir() + File.separator + APK + File.separator + channelCode + File.separator;
        log.info("appPackageVer_2-outPath:" + outPath);

        File outdir = new File(outPath);
        if (!outdir.exists()) {
            outdir.mkdirs();
        }

        String fileName = sourceFile.getName();
        File target = new File(outdir, fileName);

        try {
            String checkResult = checkApkFormat(sourceFile);
            if (checkResult != null) {
                log.error("APK文件格式错误: {}", checkResult);
                json.put("resultCode", ResultCodes.ERROR_CODE);
                json.put("resultMsg", "APK文件格式错误: " + checkResult);
                return json.toString();
            }

            log.info("签名检测结果(预查询): V1={}, V2={}, V3={}", signatureVer.getIsV1OK(), signatureVer.getIsV2OK(), signatureVer.getIsV3OK());

            boolean success = false;

            if (signatureVer.getIsV2OK() || signatureVer.getIsV1OK() || signatureVer.getIsV3OK()) {
                success = channelPackageWithSignature(sourceFile, target, channelCode, signatureVer);
                if (success) {
                    log.info("Walle打渠道号包成功---------------");
                }
            } else {
                log.error("无法检测到APK签名信息---------------");
                json.put("resultCode", ResultCodes.ERROR_CODE);
                json.put("resultMsg", "无法检测到APK签名信息");
                return json.toString();
            }

            if (!success) {
                log.error("Walle分包失败---------------");
                json.put("resultCode", ResultCodes.ERROR_CODE);
                json.put("resultMsg", "Walle分包失败");
                return json.toString();
            }

            json.put("resultCode", ResultCodes.SUCCESS_CODE);
            json.put("resultMsg", ResultCodes.SUCCESS_MSG);

        } catch (Exception e) {
            log.error("apk打包渠道号失败", e);
            json.put("resultCode", ResultCodes.ERROR_CODE);
            json.put("resultMsg", "打包失败: " + e.getMessage());
            return json.toString();
        }

        String downloadUrl = ReadPropertity.getProperty("DOWNLOAD_URL");
        pkgApkPathEntity.setApkDownloadUrl(downloadUrl + APK + File.separator + channelCode + File.separator + target.getName());
        pkgApkPathEntity.setApkDownloadPath(outPath + target.getName());
        pkgApkPathEntity.setChannelCode(channelCode);

        long time2 = System.currentTimeMillis();
        log.info("单渠道打包耗时:" + (time2 - time1) + "ms");
        log.info("------------------------打包方式打包渠道号,完成--------------------------");

        if (FileHelpUtils.isExistFile(outPath + target.getName())) {
            json.put("resultCode", ResultCodes.SUCCESS_CODE);
            json.put("resultMsg", ResultCodes.SUCCESS_MSG);
            json.put("pkgApkPathEntity", pkgApkPathEntity);
        } else {
            json.put("resultCode", ResultCodes.ERROR_CODE);
            json.put("resultMsg", ResultCodes.ERROR_MSG);
        }

        return json.toString();
    }

    private boolean channelPackageWithSignature(File sourceFile, File target, String channelCode, SignatureVersionEntity signatureVer) {
        try {
            //判断V1V2签名
            log.error(signatureVer.getIsV2OK()+"，signatureVer:::::"+signatureVer);
            if(signatureVer.getIsV2OK()){
                if(target.exists()){
                    log.error(target+"，:::::"+target.exists());
                    cn.hutool.core.io.FileUtil.del(target);
                    log.error("file deleted,{}",target);
                }
                cn.hutool.core.io.FileUtil.copyFile(sourceFile,target);
                ChannelWriter.put(target,channelCode);
                log.error("V2签名打渠道号包成功---------------");
                return true;
            }else {
                if (MCPTool.nioTransferCopyLarge(sourceFile, target)) {
                    MCPTool.write(target, channelCode, null);
                    log.error("V1签名打渠道号包成功---------------");
                    return true;
                }else {
                    log.error("V1签名打渠道号包失败---------------");
                    return false;
                }
            }
        } catch (IOException e) {
            log.error("apk打包渠道号失败",e);
            return false;
        } catch (Exception e) {
            log.error("apk打包渠道号失败",e);
            return false;
        }
    }

    private boolean channelPackageWithWalle(File source, File target, String channelCode) {
        try {
            String javaHome = System.getProperty("java.home");

            String resolvedWalleJar = resolveWalleJarPath();
            log.info("Walle jar path: {}", resolvedWalleJar);

            File jarFile = new File(resolvedWalleJar);
            if (!jarFile.exists()) {
                log.error("Walle CLI jar not found at: {}", resolvedWalleJar);
                return false;
            }

            String[] command = {
                javaHome + "/bin/java",
                "-Dfile.encoding=UTF-8",
                "-Dsun.jnu.encoding=UTF-8",
                "-jar",
                jarFile.getAbsolutePath(),
                "put",
                source.getAbsolutePath(),
                target.getAbsolutePath(),
                "--channel",
                channelCode
            };

            log.info("执行Walle命令: java -jar {} put {} {} --channel {}",
                jarFile.getName(), source.getName(), target.getName(), channelCode);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(source.getParentFile().getParentFile().getParentFile());

            Map<String, String> env = pb.environment();
            env.put("LANG", "zh_CN.UTF-8");
            env.put("LC_ALL", "zh_CN.UTF-8");
            env.put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.info("Walle: {}", line);
                }
            }

            int exitCode = process.waitFor();
            log.info("Walle执行完成: exitCode={}", exitCode);

            if (exitCode != 0) {
                log.error("Walle执行失败, output: {}", output);
                return false;
            }

            return target.exists() && target.length() > 0;

        } catch (Exception e) {
            log.error("Walle分包异常", e);
            return false;
        }
    }

    private String resolveWalleJarPath() {
        // 1. 检查配置的路径
        if (walleJarPath != null && new File(walleJarPath).exists()) {
            return walleJarPath;
        }

        String userDir = System.getProperty("user.dir");
        File projectRoot = new File(userDir);

        // 2. 检查开发环境路径
        File walleJar = new File(projectRoot, "src/main/resources/lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 3. 检查带版本号的jar
        walleJar = new File(projectRoot, "src/main/resources/lib/walle-cli-all-1.1.6.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 4. 检查相对于运行目录的lib文件夹
        walleJar = new File(projectRoot, "lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 5. 检查带版本号的jar
        walleJar = new File(projectRoot, "lib/walle-cli-all-1.1.6.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 6. 检查当前目录下的lib文件夹
        walleJar = new File("lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 7. 检查app目录下的lib文件夹（jpackage打包结构）
        walleJar = new File(projectRoot, "app/lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 8. 检查带版本号的jar
        walleJar = new File(projectRoot, "app/lib/walle-cli-all-1.1.6.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 9. 检查src/main/resources路径
        walleJar = new File("src/main/resources/lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 返回配置的路径（即使不存在，调用方会处理）
        return walleJarPath;
    }
    
    private String checkApkFormat(File apkFile) {
        try {
            // 检查文件大小是否为0
            if (apkFile.length() == 0) {
                return "APK文件大小为0";
            }
            
            // 检查是否是ZIP文件（APK本质是ZIP文件）
            java.util.zip.ZipFile zipFile = null;
            try {
                zipFile = new java.util.zip.ZipFile(apkFile);
                
                // 检查是否包含AndroidManifest.xml
                if (zipFile.getEntry("AndroidManifest.xml") == null) {
                    zipFile.close();
                    return "APK文件缺少AndroidManifest.xml";
                }
                
                // 检查是否包含classes.dex
                if (zipFile.getEntry("classes.dex") == null) {
                    zipFile.close();
                    return "APK文件缺少classes.dex";
                }
                
                zipFile.close();
                return null; // 格式正确
            } catch (java.util.zip.ZipException e) {
                return "APK文件格式错误：不是有效的ZIP归档文件";
            } finally {
                if (zipFile != null) {
                    try {
                        zipFile.close();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        } catch (Exception e) {
            log.error("检查APK格式时发生异常", e);
            return "检查APK格式失败: " + e.getMessage();
        }
    }
}
