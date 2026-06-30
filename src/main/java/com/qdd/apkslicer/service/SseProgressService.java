package com.qdd.apkslicer.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface SseProgressService {
    void createEmitter(String taskId);
    
    void createEmitter(String taskId, HttpServletRequest request, HttpServletResponse response);
    
    void sendProgress(String taskId, Object data);
    
    void completeTask(String taskId);
    
    void removeEmitter(String taskId);
}