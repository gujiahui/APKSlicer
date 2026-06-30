package com.qdd.apkslicer.entity;

import lombok.Data;

@Data
public class PkgApkPathEntity {
    private String apkDownloadUrl;
    private String apkDownloadPath;
    private String channelCode;
    private String qiniuUrl;
}
