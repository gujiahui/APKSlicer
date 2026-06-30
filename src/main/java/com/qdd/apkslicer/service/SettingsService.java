package com.qdd.apkslicer.service;

import com.qdd.apkslicer.entity.SystemSettings;

public interface SettingsService {

    SystemSettings getSettings();

    SystemSettings updateSettings(SystemSettings settings);
}