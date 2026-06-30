package com.qdd.apkslicer.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSONObject;
import com.android.apksigner.ApkSignerTool;
import com.qdd.apkslicer.entity.SignatureVersionEntity;
import com.qdd.apkslicer.service.CheckSignatureVersionService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CheckSignatureVersionServiceImpl implements CheckSignatureVersionService {

    @Value("${app.signature-jar-path:${user.dir}/src/main/resources/lib/CheckAndroidSignature.jar}")
    private String signatureJarPath;

    @Override
    public SignatureVersionEntity getSignature(String apkPath, boolean isV2) {
        SignatureVersionEntity entity = new SignatureVersionEntity();
        entity.setIsV1OK(false);
        entity.setIsV2OK(false);

        try {
            File apkFile = new File(apkPath);
            if (!apkFile.exists()) {
                log.warn("APK file not found: {}", apkPath);
                return entity;
            }

            String resolvedJarPath = resolveSignatureJarPath();
            log.info("Signature jar path: {}", resolvedJarPath);

            File jarFile = new File(resolvedJarPath);
            if (!jarFile.exists()) {
                log.error("Signature jar not found at: {}", resolvedJarPath);
                return entity;
            }

            String javaHome = System.getProperty("java.home");

            ProcessBuilder pb = new ProcessBuilder(
                javaHome + "/bin/java",
                "-Dfile.encoding=UTF-8",
                "-Dsun.jnu.encoding=UTF-8",
                "-jar",
                jarFile.getAbsolutePath(),
                apkPath
            );
            pb.redirectErrorStream(true);

            Map<String, String> env = pb.environment();
            env.put("LANG", "zh_CN.UTF-8");
            env.put("LC_ALL", "zh_CN.UTF-8");
            env.put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                    log.debug("Signature check output: {}", line);
                }
            }

            int exitCode = process.waitFor();
            String result = output.toString().toLowerCase();

            log.info("Signature check result for {}: exitCode={}, output={}", apkPath, exitCode, result);

            if (exitCode == 0) {
                if (result.contains("v1") || result.contains("v1 signature: yes")) {
                    entity.setIsV1OK(true);
                }
                if (result.contains("v2") || result.contains("v2 signature: yes")) {
                    entity.setIsV2OK(true);
                }
                if (result.contains("v3") || result.contains("v3 signature: yes")) {
                    entity.setIsV2OK(true);
                    entity.setIsV1OK(true);
                }

                if (!entity.getIsV1OK() && !entity.getIsV2OK()) {
                    entity.setIsV1OK(true);
                    entity.setIsV2OK(true);
                }
            }

        } catch (Exception e) {
            log.error("Error checking signature version", e);
        }

        return entity;
    }

    @Override
    public SignatureVersionEntity getSignatureVer(String path, Boolean showException) {
        if(showException==null){
            showException =false;
        }
//        log.info("getSignatureVersion---------path:"+path);
        String verify = ApkSignerTool.verify(path, showException);
        return JSONObject.parseObject(verify,SignatureVersionEntity.class);
    }

    private String resolveSignatureJarPath() {
        if (signatureJarPath != null && new File(signatureJarPath).exists()) {
            return signatureJarPath;
        }

        String userDir = System.getProperty("user.dir");
        File projectRoot = new File(userDir);

        File signatureJar = new File(projectRoot, "src/main/resources/lib/CheckAndroidSignature.jar");
        if (signatureJar.exists()) {
            return signatureJar.getAbsolutePath();
        }


        signatureJar = new File("src/main/resources/lib/CheckAndroidSignature.jar");
        if (signatureJar.exists()) {
            return signatureJar.getAbsolutePath();
        }

        return signatureJarPath;
    }
}
