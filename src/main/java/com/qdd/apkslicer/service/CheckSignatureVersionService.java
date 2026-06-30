package com.qdd.apkslicer.service;

import com.qdd.apkslicer.entity.SignatureVersionEntity;

public interface CheckSignatureVersionService {
    SignatureVersionEntity getSignature(String apkPath, boolean isV2);


    SignatureVersionEntity getSignatureVer(String path, Boolean showException);
}
