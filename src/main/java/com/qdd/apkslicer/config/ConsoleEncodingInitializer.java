package com.qdd.apkslicer.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ConsoleEncodingInitializer implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        if (isWindows()) {
            setConsoleEncoding();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase()
                .contains("windows");
    }

    private void setConsoleEncoding() {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "chcp", "65001");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.setProperty("file.encoding", "UTF-8");
                System.setProperty("sun.jnu.encoding", "UTF-8");
                log.info("Windows控制台编码已设置为UTF-8");
            } else {
                log.warn("Windows控制台编码设置命令返回非零退出码: {}", exitCode);
            }
        } catch (Exception e) {
            log.warn("Windows控制台编码设置失败，将使用系统默认编码: {}", e.getMessage());
        }
    }
}