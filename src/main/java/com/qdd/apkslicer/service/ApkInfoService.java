package com.qdd.apkslicer.service;

import com.qdd.apkslicer.entity.ApkInfoEntity;

public interface ApkInfoService {
    ApkInfoEntity getApkInfo(String apkPath);
}
