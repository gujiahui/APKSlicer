package com.qdd.apkslicer.entity;

import lombok.Data;

@Data
public class SystemSettings {

    private boolean breakpointUploadEnabled = false;
    private int breakpointUploadThreshold = 100;
    private int breakpointUploadMaxTasks = 1;
    private boolean breakpointDownloadEnabled = false;
    private int breakpointDownloadThreshold = 100;
    private int breakpointDownloadMaxTasks = 1;
    private boolean debugLogEnabled = false;
    private boolean overwriteEnabled = false;

    public static SystemSettings defaults() {
        return new SystemSettings();
    }
}