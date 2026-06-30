package com.qdd.apkslicer.service;

import com.qdd.apkslicer.entity.SignatureVersionEntity;

public interface ApkChannelService {
    String appPackageVer_2(String apkLocalPath, String channelCode, String password);

    String appPackageVer_2(String apkLocalPath, String channelCode, String password, SignatureVersionEntity signatureVer);
}
