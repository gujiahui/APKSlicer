package com.qdd.apkslicer.service.impl;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.qdd.apkslicer.service.SseProgressService;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SseProgressServiceImpl implements SseProgressService {

    private static final long HEARTBEAT_INTERVAL = 10 * 1000L;
    
    private final Map<String, CopyOnWriteArrayList<AsyncContext>> clientContexts = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> heartbeatFutures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(4);

    @Override
    public void createEmitter(String taskId, HttpServletRequest request, HttpServletResponse response) {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        
        try {
            AsyncContext asyncContext = request.startAsync();
            asyncContext.setTimeout(30 * 60 * 1000L);
            PrintWriter writer = asyncContext.getResponse().getWriter();
            writer.write("event: connected\ndata: {\"taskId\":\"" + taskId + "\"}\n\n");
            writer.flush();
            
            clientContexts.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(asyncContext);
            lastActivityTime.put(taskId, System.currentTimeMillis());
            
            startHeartbeat(taskId);
            
            log.info("[SSE] 创建SSE连接成功: taskId={}", taskId);
            
        } catch (IOException e) {
            log.error("[SSE] 创建SSE连接失败: taskId={}", taskId, e);
        }
    }
    
    private void startHeartbeat(String taskId) {
        stopHeartbeat(taskId);
        
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(() -> {
            CopyOnWriteArrayList<AsyncContext> contexts = clientContexts.get(taskId);
            if (contexts == null || contexts.isEmpty()) {
                stopHeartbeat(taskId);
                return;
            }
            
            String heartbeat = "event: heartbeat\ndata: {}\n\n";
            
            for (AsyncContext ctx : contexts) {
                try {
                    PrintWriter writer = ctx.getResponse().getWriter();
                    writer.write(heartbeat);
                    writer.flush();
                } catch (Exception e) {
                    log.warn("[SSE] 心跳发送失败: taskId={}, 异常={}, 移除失效客户端", taskId, e.getClass().getSimpleName());
                    contexts.remove(ctx);
                    try { ctx.complete(); } catch (Exception ignored) {}
                }
            }
            
            if (contexts.isEmpty()) {
                clientContexts.remove(taskId);
                stopHeartbeat(taskId);
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
        
        heartbeatFutures.put(taskId, future);
    }
    
    private void stopHeartbeat(String taskId) {
        ScheduledFuture<?> future = heartbeatFutures.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }
    }

    @Override
    public void sendProgress(String taskId, Object data) {
        CopyOnWriteArrayList<AsyncContext> contexts = clientContexts.get(taskId);
        if (contexts != null) {
            String jsonData = JSON.toJSONString(data);
            String message = "event: progress\ndata: " + jsonData + "\n\n";
            
            for (AsyncContext ctx : contexts) {
                try {
                    PrintWriter writer = ctx.getResponse().getWriter();
                    writer.write(message);
                    writer.flush();
                } catch (Exception e) {
                    log.warn("[SSE] 进度推送失败: taskId={}, 异常={}", taskId, e.getClass().getSimpleName());
                    contexts.remove(ctx);
                    try { ctx.complete(); } catch (Exception ignored) {}
                }
            }
            
            lastActivityTime.put(taskId, System.currentTimeMillis());
            log.debug("[SSE] 进度推送成功: taskId={}, dataSize={}", taskId, jsonData.length());
            
            if (contexts.isEmpty()) {
                clientContexts.remove(taskId);
            }
        }
    }

    @Override
    public void completeTask(String taskId) {
        stopHeartbeat(taskId);
        CopyOnWriteArrayList<AsyncContext> contexts = clientContexts.remove(taskId);
        if (contexts != null) {
            String message = "event: complete\ndata: {\"taskId\":\"" + taskId + "\"}\n\n";
            
            for (AsyncContext ctx : contexts) {
                try {
                    PrintWriter writer = ctx.getResponse().getWriter();
                    writer.write(message);
                    writer.flush();
                    log.info("[SSE] complete事件发送成功: taskId={}", taskId);
                } catch (Exception e) {
                    log.warn("[SSE] complete事件发送失败: taskId={}, 异常={}", taskId, e.getClass().getSimpleName());
                } finally {
                    try { ctx.complete(); } catch (Exception ignored) {}
                }
            }
            
            log.info("[SSE] 任务SSE连接已关闭: taskId={}", taskId);
        } else {
            log.info("[SSE] 无SSE客户端需要关闭: taskId={}", taskId);
        }
    }

    @Override
    public void removeEmitter(String taskId) {
        stopHeartbeat(taskId);
        CopyOnWriteArrayList<AsyncContext> contexts = clientContexts.remove(taskId);
        if (contexts != null) {
            for (AsyncContext ctx : contexts) {
                try { ctx.complete(); } catch (Exception e) {
                    log.warn("[SSE] 关闭AsyncContext失败: taskId={}", taskId);
                }
            }
        }
    }
    
    @Override
    public void createEmitter(String taskId) {
    }
}