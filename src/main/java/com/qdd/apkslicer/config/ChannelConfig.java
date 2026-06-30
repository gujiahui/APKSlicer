package com.qdd.apkslicer.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "channels")
public class ChannelConfig {
    
    private List<String> defaults = new ArrayList<>();
    private List<String> options = new ArrayList<>();
    private List<String> levels = new ArrayList<>();
    private List<String> levelDefaultChannels = new ArrayList<>(List.of(
            "channel_baidu", "channel_360", "channel_default", "channel_sg"
    ));
}