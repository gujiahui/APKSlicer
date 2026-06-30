package com.qdd.apkslicer.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.qdd.apkslicer.entity.ApkInfoEntity;
import com.qdd.apkslicer.entity.SignatureVersionEntity;
import com.qdd.apkslicer.service.ApkInfoService;
import com.qdd.apkslicer.service.CheckSignatureVersionService;
import com.qdd.apkslicer.util.MCPTool;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ApkInfoServiceImpl implements ApkInfoService {

    @Value("${app.apkinfo-jar-path:${user.dir}/src/main/resources/lib/GetAPKInfo.jar}")
    private String apkInfoJarPath;

    private final CheckSignatureVersionService signatureVersionService;

    public ApkInfoServiceImpl(CheckSignatureVersionService signatureVersionService) {
        this.signatureVersionService = signatureVersionService;
    }

    @Override
    public ApkInfoEntity getApkInfo(String apkPath) {
        ApkInfoEntity entity = new ApkInfoEntity();

        try {
            File apkFile = new File(apkPath);
            if (!apkFile.exists()) {
                log.warn("APK file not found: {}", apkPath);
                entity.setErrorMessage("APK文件不存在");
                return entity;
            }

            String resolvedJarPath = resolveApkInfoJarPath();
            log.info("APKInfo jar path: {}", resolvedJarPath);

            File jarFile = new File(resolvedJarPath);
            if (!jarFile.exists()) {
                log.warn("APKInfo jar not found, using fallback method");
                return getApkInfoFallback(apkPath);
            }

            // 直接使用执行jar包的方式获取APK信息（用户要求改回）
            log.info("Using process execution to get APK info");
            String result = runApkInfoJar(jarFile, apkPath);
            
            if (result != null && !result.isEmpty()) {
                // 先尝试解析结果
                parseResultText(result, entity);
                log.info("Parsed APK info: packageName={}, versionName={}, keystoreMd5={}",
                    entity.getPackageName(), entity.getVersionName(), entity.getKeystoreMd5());
                
                // 检查是否包含严重错误（格式问题）
                boolean hasFatalError = result.contains("ApkFormatException") || 
                                       result.contains("Malformed APK") || 
                                       result.contains("not a ZIP archive");
                
                // 检查是否获取到了基本信息
                boolean hasBasicInfo = entity.getPackageName() != null && !entity.getPackageName().isEmpty();
                
                if (hasFatalError && !hasBasicInfo) {
                    // 严重错误且没有获取到基本信息，使用降级方法
                    log.error("APK parsing error detected: {}", result);
                    
                    String errorMsg = extractErrorMessage(result);
                    if (errorMsg != null) {
                        entity.setErrorMessage(errorMsg);
                    } else {
                        entity.setErrorMessage("APK解析失败，请检查文件是否正确");
                    }
                    
                    return getApkInfoFallbackWithError(apkPath, entity.getErrorMessage());
                } else if (result.contains("Exception")) {
                    // 有异常但可能已获取部分信息，只记录警告
                    log.warn("APKInfo execution contained exception but may have partial results: {}", result);
                }
            }

            // 补充签名信息
            if (entity.getIsV1OK() == null || entity.getIsV2OK() == null || entity.getIsV3() == null) {
                SignatureVersionEntity signature = signatureVersionService.getSignatureVer(apkPath, true);
                log.info("Signature version result: {}", JSONObject.toJSONString(signature));
                
                if (entity.getIsV1OK() == null) {
                    entity.setIsV1OK(signature.getIsV1OK());
                }
                if (entity.getIsV2OK() == null) {
                    entity.setIsV2OK(signature.getIsV2OK());
                }
                if (entity.getIsV2() == null) {
                    entity.setIsV2(signature.getIsV2());
                }
                if (entity.getIsV3() == null) {
                    entity.setIsV3(signature.getIsV3());
                }
                if (entity.getIsV3OK() == null) {
                    entity.setIsV3OK(signature.getIsV3OK());
                }
                if (entity.getKeystoreMd5() == null && signature.getKeystoreMd5() != null) {
                    entity.setKeystoreMd5(signature.getKeystoreMd5());
                }
            }

            String channelCode = readChannelCode(apkPath, entity.getIsV2OK());
            entity.setChannelCode(channelCode);

            buildSignatureDetail(entity);

        } catch (Exception e) {
            log.error("Error getting APK info", e);
            entity.setErrorMessage("获取APK信息失败: " + e.getMessage());
            return getApkInfoFallbackWithError(apkPath, entity.getErrorMessage());
        }

        return entity;
    }
    
    private String extractErrorMessage(String result) {
        // 尝试提取关键错误信息
        if (result.contains("ApkFormatException")) {
            int start = result.indexOf("ApkFormatException");
            int end = result.indexOf("\n", start);
            if (end > start) {
                return result.substring(start, end).trim();
            }
            return "APK格式异常";
        }
        if (result.contains("Malformed APK")) {
            return "APK文件格式错误：不是有效的ZIP归档文件";
        }
        if (result.contains("not a ZIP archive")) {
            return "APK文件格式错误：不是有效的ZIP归档文件";
        }
        if (result.contains("NullPointerException")) {
            return "解析APK时发生空指针异常";
        }
        return null;
    }
    
    private ApkInfoEntity getApkInfoFallbackWithError(String apkPath, String errorMessage) {
        ApkInfoEntity entity = new ApkInfoEntity();
        entity.setErrorMessage(errorMessage);
        
        try {
            SignatureVersionEntity signature = signatureVersionService.getSignatureVer(apkPath, true);
            entity.setIsV1OK(signature.getIsV1OK());
            entity.setIsV2(true);
            entity.setIsV2OK(signature.getIsV2OK());
            entity.setIsV3(false);
            entity.setIsV3OK(false);

            String channelCode = readChannelCode(apkPath, entity.getIsV2OK());
            entity.setChannelCode(channelCode);

            buildSignatureDetail(entity);
        } catch (Exception e) {
            log.warn("Fallback also failed: {}", e.getMessage());
        }
        
        return entity;
    }

    private String runApkInfoJar(File jarFile, String apkPath) {
        StringBuilder output = new StringBuilder();
        try {
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

            Process process = pb.start();

            // 先尝试UTF-8编码读取
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("APKInfo UTF-8 output line: {}", line);
            }

            int exitCode = process.waitFor();
            log.info("APKInfo process exit code: {}", exitCode);

        } catch (Exception e) {
            log.error("Failed to run APKInfo jar", e);
        }
        
        String result = output.toString();
        log.info("APKInfo full output (length={}):\n{}", result.length(), result);
        
        // 检查是否存在乱码（包含不可打印字符），尝试用GBK重新解析
        if (containsGarbledChars(result)) {
            log.warn("Detected garbled characters, trying GBK encoding");
            return runApkInfoJarWithGBK(jarFile, apkPath);
        }
        
        return result;
    }
    
    private String runApkInfoJarWithGBK(File jarFile, String apkPath) {
        StringBuilder output = new StringBuilder();
        try {
            String javaHome = System.getProperty("java.home");

            ProcessBuilder pb = new ProcessBuilder(
                    javaHome + "/bin/java",
                    "-Dfile.encoding=GBK",
                    "-Dsun.jnu.encoding=GBK",
                    "-jar",
                    jarFile.getAbsolutePath(),
                    apkPath
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("APKInfo GBK output line: {}", line);
            }

            int exitCode = process.waitFor();
            log.info("APKInfo GBK process exit code: {}", exitCode);

        } catch (Exception e) {
            log.error("Failed to run APKInfo jar with GBK", e);
        }
        
        String result = output.toString();
        log.info("APKInfo GBK full output (length={}):\n{}", result.length(), result);
        return result;
    }
    
    private boolean containsGarbledChars(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 检查是否包含常见的乱码字符（如\uFFFD替换字符或控制字符）
        for (char c : text.toCharArray()) {
            if (c == '\uFFFD' || (c >= 0x00 && c <= 0x1F && c != '\n' && c != '\r' && c != '\t')) {
                return true;
            }
        }
        return false;
    }

    private void parseResultText(String result, ApkInfoEntity entity) {
        if (result == null || result.isEmpty()) {
            return;
        }

        try {
            JSONObject json = JSONObject.parseObject(result);
            if (json != null) {
                parseJsonToEntity(json, entity);
                return;
            }
        } catch (Exception e) {
            log.debug("Result is not pure JSON, parsing as text");
        }

        parseTextToEntity(result, entity);
    }

    private Object invokeApkUtilDirectly(File jarFile, String apkPath) {
        log.info("Attempting direct invocation of ApkUtil");
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        
        try {
            java.net.URL[] urls = new java.net.URL[]{jarFile.toURI().toURL()};
            java.net.URLClassLoader classLoader = new java.net.URLClassLoader(urls, originalClassLoader);
            
            try {
                Thread.currentThread().setContextClassLoader(classLoader);
                
                Class<?> apkInfoClass = classLoader.loadClass("com.bihe0832.packageinfo.bean.ApkInfo");
                log.info("Loaded ApkInfo class: {}", apkInfoClass.getName());
                
                Object apkInfoInstance = apkInfoClass.getDeclaredConstructor().newInstance();
                log.info("Created ApkInfo instance");
                
                Class<?> apkUtilClass = classLoader.loadClass("com.bihe0832.packageinfo.utils.ApkUtil");
                log.info("Loaded ApkUtil class: {}", apkUtilClass.getName());
                
                Method updateAPKInfoMethod = apkUtilClass.getMethod("updateAPKInfo", 
                    String.class, apkInfoClass, boolean.class);
                log.info("Found updateAPKInfo method");
                
                log.info("Invoking updateAPKInfo with apkPath={}", apkPath);
                updateAPKInfoMethod.invoke(null, apkPath, apkInfoInstance, true);
                
                log.info("Successfully invoked ApkUtil.updateAPKInfo directly");
                return apkInfoInstance;
                
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
                try {
                    classLoader.close();
                } catch (Exception e) {
                    log.warn("Error closing classloader", e);
                }
            }
            
        } catch (Exception e) {
            log.warn("Failed to invoke ApkUtil directly: {}", e.getMessage(), e);
            return null;
        }
    }

    private void parseApkInfoObject(Object apkInfo, ApkInfoEntity entity) {
        try {
            Class<?> clazz = apkInfo.getClass();
            log.info("Parsing ApkInfo object of class: {}", clazz.getName());
            
            entity.setPackageName(getFieldValue(clazz, apkInfo, "packageName"));
            entity.setVersionName(getFieldValue(clazz, apkInfo, "versionName"));
            
            String versionCodeStr = getFieldValue(clazz, apkInfo, "versionCode");
            if (versionCodeStr != null && !versionCodeStr.isEmpty()) {
                try {
                    entity.setVersionCode(Integer.parseInt(versionCodeStr));
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse versionCode: {}", versionCodeStr);
                }
            }
            
            String signature = getFieldValue(clazz, apkInfo, "signature");
            if (signature != null && !signature.isEmpty() && !"null".equalsIgnoreCase(signature)) {
                entity.setKeystoreMd5(signature);
            }
            
            String minSdkStr = getFieldValue(clazz, apkInfo, "minSdkVersion");
            if (minSdkStr != null && !minSdkStr.isEmpty()) {
                try {
                    entity.setMinSdkVersion(Integer.parseInt(minSdkStr));
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse minSdkVersion: {}", minSdkStr);
                }
            }
            
            String targetSdkStr = getFieldValue(clazz, apkInfo, "targetSdkVersion");
            if (targetSdkStr != null && !targetSdkStr.isEmpty()) {
                try {
                    entity.setTargetSdkVersion(Integer.parseInt(targetSdkStr));
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse targetSdkVersion: {}", targetSdkStr);
                }
            }
            
            entity.setIsV1OK(getBooleanFieldValue(clazz, apkInfo, "isV1SignatureOK"));
            entity.setIsV2(getBooleanFieldValue(clazz, apkInfo, "isV2Signature"));
            entity.setIsV2OK(getBooleanFieldValue(clazz, apkInfo, "isV2SignatureOK"));
            entity.setIsV3(getBooleanFieldValue(clazz, apkInfo, "isV3Signature"));
            entity.setIsV3OK(getBooleanFieldValue(clazz, apkInfo, "isV3SignatureOK"));
            
            log.info("Parsed APK info: package={}, version={}, signature={}, isV1OK={}, isV2={}, isV2OK={}, isV3={}, isV3OK={}",
                entity.getPackageName(), entity.getVersionName(), entity.getKeystoreMd5(),
                entity.getIsV1OK(), entity.getIsV2(), entity.getIsV2OK(), entity.getIsV3(), entity.getIsV3OK());
            
        } catch (Exception e) {
            log.warn("Failed to parse ApkInfo object", e);
        }
    }

    private String getFieldValue(Class<?> clazz, Object instance, String fieldName) {
        try {
            java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(instance);
            if (value == null) {
                return null;
            }
            String strValue = value.toString();
            if (strValue.isEmpty() || "null".equalsIgnoreCase(strValue)) {
                return null;
            }
            return strValue.trim();
        } catch (NoSuchFieldException e) {
            log.debug("Field {} not found in class {}", fieldName, clazz.getName());
            return null;
        } catch (Exception e) {
            log.debug("Failed to get field {}: {}", fieldName, e.getMessage());
            return null;
        }
    }

    private Boolean getBooleanFieldValue(Class<?> clazz, Object instance, String fieldName) {
        try {
            java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getBoolean(instance);
        } catch (NoSuchFieldException e) {
            log.debug("Boolean field {} not found in class {}", fieldName, clazz.getName());
            return null;
        } catch (Exception e) {
            log.debug("Failed to get boolean field {}: {}", fieldName, e.getMessage());
            return null;
        }
    }

    private void parseJsonToEntity(JSONObject json, ApkInfoEntity entity) {
        if (json.containsKey("packageName")) {
            entity.setPackageName(json.getString("packageName"));
        }
        if (json.containsKey("versionName")) {
            entity.setVersionName(json.getString("versionName"));
        }
        if (json.containsKey("versionCode")) {
            entity.setVersionCode(json.getInteger("versionCode"));
        }
        if (json.containsKey("keystoreMd5")) {
            entity.setKeystoreMd5(json.getString("keystoreMd5"));
        }
        if (json.containsKey("minSdkVersion")) {
            entity.setMinSdkVersion(json.getInteger("minSdkVersion"));
        }
        if (json.containsKey("targetSdkVersion")) {
            entity.setTargetSdkVersion(json.getInteger("targetSdkVersion"));
        }
        if (json.containsKey("isV1OK")) {
            entity.setIsV1OK(json.getBoolean("isV1OK"));
        }
        if (json.containsKey("isV2")) {
            entity.setIsV2(json.getBoolean("isV2"));
        }
        if (json.containsKey("isV2OK")) {
            entity.setIsV2OK(json.getBoolean("isV2OK"));
        }
        if (json.containsKey("isV3")) {
            entity.setIsV3(json.getBoolean("isV3"));
        }
        if (json.containsKey("isV3OK")) {
            entity.setIsV3OK(json.getBoolean("isV3OK"));
        }
        if (json.containsKey("signature")) {
            entity.setKeystoreMd5(json.getString("signature"));
        }
    }

    private void parseTextToEntity(String text, ApkInfoEntity entity) {
        java.util.Map<String, String> fieldMap = new java.util.HashMap<>();

        java.util.regex.Pattern PACKAGE_PATTERN = java.util.regex.Pattern.compile("包\\s*名\\s*[:：]\\s*(\\S+)");
        java.util.regex.Pattern VERSION_NAME_PATTERN = java.util.regex.Pattern.compile("版本\\s*名\\s*[:：]\\s*(\\S+)");
        java.util.regex.Pattern VERSION_CODE_PATTERN = java.util.regex.Pattern.compile("版本\\s*号\\s*[:：]\\s*(\\d+)");
        java.util.regex.Pattern MD5_PATTERN = java.util.regex.Pattern.compile("签名文件\\s*MD5\\s*[:：]\\s*([a-fA-F0-9]+)");
        java.util.regex.Pattern MIN_SDK_PATTERN = java.util.regex.Pattern.compile("minSdkVersion\\s*[:：]\\s*(\\d+)");
        java.util.regex.Pattern TARGET_SDK_PATTERN = java.util.regex.Pattern.compile("targetSdkVersion\\s*[:：]\\s*(\\d+)");
        java.util.regex.Pattern V1_OK_PATTERN = java.util.regex.Pattern.compile("V1签名验证通过\\s*[:：]\\s*(true|false)");
        java.util.regex.Pattern USE_V2_PATTERN = java.util.regex.Pattern.compile("使用V2签名\\s*[:：]\\s*(true|false)");
        java.util.regex.Pattern V2_OK_PATTERN = java.util.regex.Pattern.compile("V2签名验证通过\\s*[:：]\\s*(true|false)");
        java.util.regex.Pattern USE_V3_PATTERN = java.util.regex.Pattern.compile("使用V3签名\\s*[:：]\\s*(true|false)");
        java.util.regex.Pattern V3_OK_PATTERN = java.util.regex.Pattern.compile("V3签名验证通过\\s*[:：]\\s*(true|false)");
        java.util.regex.Pattern SIGNATURE_DETAIL_PATTERN = java.util.regex.Pattern.compile("签名验证详细信息\\s*[:：]\\s*(\\{[^\\}]+\\})");

        extractField(text, PACKAGE_PATTERN, fieldMap, "packageName");
        extractField(text, VERSION_NAME_PATTERN, fieldMap, "versionName");
        extractField(text, VERSION_CODE_PATTERN, fieldMap, "versionCode");
        extractField(text, MD5_PATTERN, fieldMap, "keystoreMd5");
        extractField(text, MIN_SDK_PATTERN, fieldMap, "minSdkVersion");
        extractField(text, TARGET_SDK_PATTERN, fieldMap, "targetSdkVersion");
        extractField(text, V1_OK_PATTERN, fieldMap, "isV1OK");
        extractField(text, USE_V2_PATTERN, fieldMap, "isV2");
        extractField(text, V2_OK_PATTERN, fieldMap, "isV2OK");
        extractField(text, USE_V3_PATTERN, fieldMap, "isV3");
        extractField(text, V3_OK_PATTERN, fieldMap, "isV3OK");

        if (fieldMap.containsKey("packageName")) {
            entity.setPackageName(fieldMap.get("packageName"));
        }
        if (fieldMap.containsKey("versionName")) {
            entity.setVersionName(fieldMap.get("versionName"));
        }
        if (fieldMap.containsKey("versionCode")) {
            try {
                entity.setVersionCode(Integer.parseInt(fieldMap.get("versionCode")));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse versionCode: {}", fieldMap.get("versionCode"));
            }
        }
        if (fieldMap.containsKey("keystoreMd5")) {
            entity.setKeystoreMd5(fieldMap.get("keystoreMd5"));
        }
        if (fieldMap.containsKey("minSdkVersion")) {
            try {
                entity.setMinSdkVersion(Integer.parseInt(fieldMap.get("minSdkVersion")));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse minSdkVersion");
            }
        }
        if (fieldMap.containsKey("targetSdkVersion")) {
            try {
                entity.setTargetSdkVersion(Integer.parseInt(fieldMap.get("targetSdkVersion")));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse targetSdkVersion");
            }
        }
        if (fieldMap.containsKey("isV1OK")) {
            entity.setIsV1OK(Boolean.parseBoolean(fieldMap.get("isV1OK")));
        }
        if (fieldMap.containsKey("isV2")) {
            entity.setIsV2(Boolean.parseBoolean(fieldMap.get("isV2")));
        }
        if (fieldMap.containsKey("isV2OK")) {
            entity.setIsV2OK(Boolean.parseBoolean(fieldMap.get("isV2OK")));
        }
        if (fieldMap.containsKey("isV3")) {
            entity.setIsV3(Boolean.parseBoolean(fieldMap.get("isV3")));
        }
        if (fieldMap.containsKey("isV3OK")) {
            entity.setIsV3OK(Boolean.parseBoolean(fieldMap.get("isV3OK")));
        }
        
        // 如果MD5值为null，尝试从签名验证详细信息中提取
        if (entity.getKeystoreMd5() == null) {
            java.util.regex.Matcher matcher = SIGNATURE_DETAIL_PATTERN.matcher(text);
            if (matcher.find()) {
                String signatureDetailJson = matcher.group(1);
                log.info("Found signature detail JSON: {}", signatureDetailJson);
                try {
                    JSONObject json = JSONObject.parseObject(signatureDetailJson);
                    if (json.containsKey("keystoreMd5")) {
                        String keystoreMd5 = json.getString("keystoreMd5");
                        if (keystoreMd5 != null && !keystoreMd5.isEmpty() && !"null".equalsIgnoreCase(keystoreMd5)) {
                            entity.setKeystoreMd5(keystoreMd5);
                            log.info("Extracted keystoreMd5 from signature detail: {}", keystoreMd5);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse signature detail JSON: {}", e.getMessage());
                }
            }
        }
    }

    private void extractField(String text, java.util.regex.Pattern pattern, java.util.Map<String, String> fieldMap, String fieldName) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            fieldMap.put(fieldName, matcher.group(1).trim());
        }
    }

    private void buildSignatureDetail(ApkInfoEntity entity) {
        JSONObject signatureDetail = new JSONObject();
        signatureDetail.put("ret", 0);
        signatureDetail.put("msg", "");
        signatureDetail.put("isV1OK", entity.getIsV1OK());
        signatureDetail.put("isV2", entity.getIsV2());
        signatureDetail.put("isV2OK", entity.getIsV2OK());
        signatureDetail.put("isV3", entity.getIsV3());
        signatureDetail.put("isV3OK", entity.getIsV3OK());
        signatureDetail.put("keystoreMd5", entity.getKeystoreMd5());
        entity.setSignatureDetail(signatureDetail.toJSONString());
    }

    private String resolveApkInfoJarPath() {
        // 1. 检查配置的路径
        if (apkInfoJarPath != null && new File(apkInfoJarPath).exists()) {
            return apkInfoJarPath;
        }

        String userDir = System.getProperty("user.dir");
        File projectRoot = new File(userDir);

        // 2. 检查开发环境路径
        File apkInfoJar = new File(projectRoot, "src/main/resources/lib/GetAPKInfo.jar");
        if (apkInfoJar.exists()) {
            return apkInfoJar.getAbsolutePath();
        }

        // 3. 检查相对于运行目录的lib文件夹
        apkInfoJar = new File(projectRoot, "lib/GetAPKInfo.jar");
        if (apkInfoJar.exists()) {
            return apkInfoJar.getAbsolutePath();
        }

        // 4. 检查当前目录下的lib文件夹
        apkInfoJar = new File("lib/GetAPKInfo.jar");
        if (apkInfoJar.exists()) {
            return apkInfoJar.getAbsolutePath();
        }

        // 5. 检查app目录下的lib文件夹（jpackage打包结构）
        apkInfoJar = new File(projectRoot, "app/lib/GetAPKInfo.jar");
        if (apkInfoJar.exists()) {
            return apkInfoJar.getAbsolutePath();
        }

        // 6. 检查src/main/resources路径
        apkInfoJar = new File("src/main/resources/lib/GetAPKInfo.jar");
        if (apkInfoJar.exists()) {
            return apkInfoJar.getAbsolutePath();
        }

        // 返回配置的路径（即使不存在，调用方会处理）
        return apkInfoJarPath;
    }

    private ApkInfoEntity getApkInfoFallback(String apkPath) {
        ApkInfoEntity entity = new ApkInfoEntity();

        SignatureVersionEntity signature = signatureVersionService.getSignatureVer(apkPath, true);
        entity.setIsV1OK(signature.getIsV1OK());
        entity.setIsV2(true);
        entity.setIsV2OK(signature.getIsV2OK());
        entity.setIsV3(false);
        entity.setIsV3OK(false);

        String channelCode = readChannelCode(apkPath, entity.getIsV2OK());
        entity.setChannelCode(channelCode);

        buildSignatureDetail(entity);

        return entity;
    }

    private String readChannelCode(String apkPath, Boolean isV2OK) {
        if (isV2OK == null || !isV2OK) {
            try {
                String channel = MCPTool.readContent(new File(apkPath), null);
                log.info("Read channel from MCPTool: {}", channel);
                return channel;
            } catch (Exception e) {
                log.warn("Failed to read channel with MCPTool", e);
                return null;
            }
        }

        String resolvedJarPath = resolveWalleJarPath();
        File jarFile = new File(resolvedJarPath);
        if (!jarFile.exists()) {
            log.warn("Walle jar not found, trying MCPTool");
            return readChannelWithMCPTool(new File(apkPath));
        }

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            java.net.URL[] urls = new java.net.URL[]{jarFile.toURI().toURL()};
            java.net.URLClassLoader classLoader = new java.net.URLClassLoader(urls, originalClassLoader);

            try {
                Thread.currentThread().setContextClassLoader(classLoader);

                Class<?> channelReaderClass = classLoader.loadClass("com.meituan.android.walle.ChannelReader");
                Method getMethod = channelReaderClass.getMethod("get", File.class);

                Object channelInfo = getMethod.invoke(null, new File(apkPath));
                if (channelInfo != null) {
                    Method getChannelMethod = channelInfo.getClass().getMethod("getChannel");
                    String channel = (String) getChannelMethod.invoke(channelInfo);
                    log.info("Read channel from Walle: {}", channel);
                    return channel;
                }
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
                try {
                    classLoader.close();
                } catch (Exception e) {
                    log.warn("Error closing classloader", e);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read channel with Walle, trying MCPTool", e);
            return readChannelWithMCPTool(new File(apkPath));
        }
        return null;
    }

    private String readChannelWithMCPTool(File apkFile) {
        try {
            String channel = MCPTool.readContent(apkFile, null);
            log.info("Read channel from MCPTool: {}", channel);
            return channel;
        } catch (Exception e) {
            log.warn("Failed to read channel with MCPTool", e);
            return null;
        }
    }

    private String resolveWalleJarPath() {
        String userDir = System.getProperty("user.dir");
        File projectRoot = new File(userDir);

        // 1. 检查开发环境路径
        File walleJar = new File(projectRoot, "src/main/resources/lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 2. 检查带版本号的jar
        walleJar = new File(projectRoot, "src/main/resources/lib/walle-cli-all-1.1.6.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 3. 检查相对于运行目录的lib文件夹
        walleJar = new File(projectRoot, "lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 4. 检查带版本号的jar
        walleJar = new File(projectRoot, "lib/walle-cli-all-1.1.6.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 5. 检查当前目录下的lib文件夹
        walleJar = new File("lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 6. 检查app目录下的lib文件夹（jpackage打包结构）
        walleJar = new File(projectRoot, "app/lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 7. 检查带版本号的jar
        walleJar = new File(projectRoot, "app/lib/walle-cli-all-1.1.6.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        // 8. 检查src/main/resources路径
        walleJar = new File("src/main/resources/lib/walle-cli-all.jar");
        if (walleJar.exists()) {
            return walleJar.getAbsolutePath();
        }

        return "lib/walle-cli-all.jar";
    }
}
