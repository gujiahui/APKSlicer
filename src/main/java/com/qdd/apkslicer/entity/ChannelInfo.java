package com.qdd.apkslicer.entity;

import lombok.Data;

@Data
public class ChannelInfo {
    private String channelCode;
    private String qiniuUrl;
    private boolean success;
    private String errorMessage;
}
