package com.qdd.apkslicer.entity;

import lombok.Data;

@Data
public class SignatureVersionEntity {
    private int ret;
    private String msg;
    private Boolean isV1OK;
    private Boolean isV2;
    private Boolean isV2OK;
    private Boolean isV3;
    private Boolean isV3OK;
    private String keystoreMd5;
}
