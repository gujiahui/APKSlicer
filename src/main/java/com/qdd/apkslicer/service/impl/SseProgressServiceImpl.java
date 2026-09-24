package com.qdd.apkslicer.service.impl;

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

    private final Map<String, CopyOnWriteArrayList<SseClient>> clientContexts = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> heartbeatFutures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(4);

    /**
     * 单条 SSE 连接的写入句柄。
     *
     * <p>Tomcat 的 {@code OutputBuffer} / {@code C2BConverter} 不是线程安全的：它们内部缓存了可变
     * 的 {@code CharBuffer}/{@code ByteBuffer} 以及一个<b>有状态</b>的 {@code CharsetEncoder}。
     * 而本服务的进度推送来自多个分包工作线程 + 心跳线程 + 建连的请求线程，一旦并发写同一个
     * Response 的 Writer，缓存 Buffer 的 limit/position 就会被打乱，抛出
     * {@code CoderMalfunctionError: java.lang.IllegalArgumentException: newPosition > limit}，
     * 由于它是 {@code Error} 而非 {@code Exception}，会穿透子任务的 catch 导致整条渠道分包失败。
     *
     * 因此每条连接都必须通过 {@link #send(String)} 串行化写入。
     */
    private static final class SseClient {

        private final AsyncContext asyncContext;
        private final Object writeLock = new Object();
        private PrintWriter writer;
        private volatile boolean closed;

        SseClient(AsyncContext asyncContext) {
            this.asyncContext = asyncContext;
        }

        /**
         * 在连接级锁内完成 write + flush。
         *
         * @return true 表示写入成功；false 表示连接已失效并已被关闭，调用方应将其移除
         */
        boolean send(String payload) {
            synchronized (writeLock) {
                if (closed) {
                    return false;
                }
                try {
                    if (writer == null) {
                        // getWriter() 在 OutputBuffer 中是懒加载且无锁，缓存后只在本锁内调用一次
                        writer = asyncContext.getResponse().getWriter();
                    }
                    writer.write(payload);
                    writer.flush();
                    return true;
                } catch (Throwable t) {
                    // CoderMalfunctionError 属于 Error，必须用 Throwable 兜住，
                    // 否则会冒泡到业务线程并中断分包流程
                    log.warn("[SSE] 写入失败，关闭该连接: {}", t.toString());
                    closeLocked();
                    return false;
                }
            }
        }

        void close() {
            synchronized (writeLock) {
                closeLocked();
            }
        }

        private void closeLocked() {
            if (closed) {
                return;
            }
            closed = true;
            writer = null;
            try {
                asyncContext.complete();
            } catch (Throwable ignored) {
                // 连接可能已由容器关闭，忽略
            }
        }
    }

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

            SseClient client = new SseClient(asyncContext);
            // 此时客户端尚未注册进列表，不会有其它线程写它，先发握手事件
            if (!client.send("event: connected\ndata: {\"taskId\":\"" + taskId + "\"}\n\n")) {
                log.warn("[SSE] 发送connected事件失败，放弃该连接: taskId={}", taskId);
                return;
            }

            clientContexts.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(client);
            lastActivityTime.put(taskId, System.currentTimeMillis());

            startHeartbeat(taskId);

            log.info("[SSE] 创建SSE连接成功: taskId={}", taskId);

        } catch (Exception e) {
            log.error("[SSE] 创建SSE连接失败: taskId={}", taskId, e);
        }
    }

    private void startHeartbeat(String taskId) {
        stopHeartbeat(taskId);

        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(() -> {
            CopyOnWriteArrayList<SseClient> clients = clientContexts.get(taskId);
            if (clients == null || clients.isEmpty()) {
                stopHeartbeat(taskId);
                return;
            }

            String heartbeat = "event: heartbeat\ndata: {}\n\n";

            for (SseClient client : clients) {
                if (!client.send(heartbeat)) {
                    clients.remove(client);
                }
            }

            if (clients.isEmpty()) {
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
        CopyOnWriteArrayList<SseClient> clients = clientContexts.get(taskId);
        if (clients == null) {
            return;
        }

        // ProgressServiceImpl 已在每任务锁内把进度冻结成 JSON 字符串传进来（避免序列化活对象）；
        // 若传入的是 String 则直接使用，避免 JSON.toJSONString 二次转义。
        String jsonData = data instanceof String ? (String) data : JSON.toJSONString(data);
        String message = "event: progress\ndata: " + jsonData + "\n\n";

        for (SseClient client : clients) {
            if (!client.send(message)) {
                clients.remove(client);
            }
        }

        lastActivityTime.put(taskId, System.currentTimeMillis());
        log.debug("[SSE] 进度推送成功: taskId={}, dataSize={}", taskId, jsonData.length());

        if (clients.isEmpty()) {
            clientContexts.remove(taskId);
        }
    }

    @Override
    public void completeTask(String taskId) {
        stopHeartbeat(taskId);
        CopyOnWriteArrayList<SseClient> clients = clientContexts.remove(taskId);
        if (clients == null) {
            log.info("[SSE] 无SSE客户端需要关闭: taskId={}", taskId);
            return;
        }

        String message = "event: complete\ndata: {\"taskId\":\"" + taskId + "\"}\n\n";

        for (SseClient client : clients) {
            try {
                if (client.send(message)) {
                    log.info("[SSE] complete事件发送成功: taskId={}", taskId);
                }
            } finally {
                client.close();
            }
        }

        log.info("[SSE] 任务SSE连接已关闭: taskId={}", taskId);
    }

    @Override
    public void removeEmitter(String taskId) {
        stopHeartbeat(taskId);
        CopyOnWriteArrayList<SseClient> clients = clientContexts.remove(taskId);
        if (clients != null) {
            for (SseClient client : clients) {
                client.close();
            }
        }
    }

    @Override
    public void createEmitter(String taskId) {
    }
}
