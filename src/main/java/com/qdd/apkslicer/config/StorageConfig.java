package com.qdd.apkslicer.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "storage")
public class StorageConfig {
    private String tempDir;
    private String outputDir;

    @PostConstruct
    public void init() {
        String userDir = System.getProperty("user.dir");
        log.info("Application working directory: {}", userDir);

        // 处理 temp 目录
        if (tempDir != null && !tempDir.isEmpty()) {
            if (isAbsolutePath(tempDir)) {
                // 绝对路径，直接使用
                log.info("Using absolute temp directory: {}", tempDir);
            } else {
                // 相对路径，转换为绝对路径
                tempDir = new File(userDir, tempDir).getAbsolutePath();
                log.info("Resolved relative temp directory to: {}", tempDir);
            }
            // 确保目录存在
            new File(tempDir).mkdirs();
        }

        // 处理 output 目录
        if (outputDir != null && !outputDir.isEmpty()) {
            if (isAbsolutePath(outputDir)) {
                // 绝对路径，直接使用
                log.info("Using absolute output directory: {}", outputDir);
            } else {
                // 相对路径，转换为绝对路径
                outputDir = new File(userDir, outputDir).getAbsolutePath();
                log.info("Resolved relative output directory to: {}", outputDir);
            }
            // 确保目录存在
            new File(outputDir).mkdirs();
        }
    }

    /**
     * 判断路径是否为绝对路径
     * 支持 Unix/Linux (/) 和 Windows (C:\, D:\ 等)
     */
    private boolean isAbsolutePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        
        // Unix/Linux 绝对路径：以 / 开头
        if (path.startsWith("/")) {
            return true;
        }
        
        // Windows 绝对路径：包含盘符 (如 C:\, D:\)
        // 或者以 \ 开头（网络路径）
        if (path.startsWith("\\") || 
            (path.length() >= 2 && path.charAt(1) == ':')) {
            return true;
        }
        
        return false;
    }
}