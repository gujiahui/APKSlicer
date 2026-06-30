package com.qdd.apkslicer.entity;

import lombok.Data;

@Data
public class ApkInfoEntity {
    private String packageName;
    private String versionName;
    private Integer versionCode;
    private String keystoreMd5;
    private Integer minSdkVersion;
    private Integer targetSdkVersion;
    private Boolean isV1OK;
    private Boolean isV2;
    private Boolean isV2OK;
    private Boolean isV3;
    private Boolean isV3OK;
    private String signatureDetail;
    private String channelCode;
    private String errorMessage;
}
