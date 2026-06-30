package com.qdd.apkslicer.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReadPropertity {
    
    @Value("${storage.output-dir:./output}")
    private String downloadUrl;
    
    public static String getProperty(String key) {
        if ("DOWNLOAD_URL".equals(key)) {
            return "http://localhost:8080/download/";
        }
        return "";
    }
    
    public String getDownloadUrl() {
        return downloadUrl;
    }
}
