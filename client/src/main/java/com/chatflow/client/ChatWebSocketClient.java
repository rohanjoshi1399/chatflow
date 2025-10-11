package com.chatflow.client;

import com.chatflow.client.metrics.MetricsCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ChatWebSocketClient extends WebSocketClient {
    private final CountDownLatch responseLatch;
    private final AtomicInteger successCount;
    private final AtomicInteger errorCount;
    private final ObjectMapper objectMapper;
    private final Semaphore inFlightSemaphore;
    private long lastSendTime;
    private String roomId;

    // For metrics collection (Part 2)
    private MetricsCollector metricsCollector;
    private String assignedRoomId;
    private boolean trackMetrics = false;

    // Constructor with semaphore (for backpressure control)
    public ChatWebSocketClient(URI serverUri, CountDownLatch responseLatch,
                               AtomicInteger successCount, AtomicInteger errorCount,
                               Semaphore inFlightSemaphore) {
        super(serverUri);
        this.responseLatch = responseLatch;
        this.successCount = successCount;
        this.errorCount = errorCount;
        this.inFlightSemaphore = inFlightSemaphore;
        this.objectMapper = new ObjectMapper();

        String path = serverUri.getPath();
        String[] parts = path.split("/");
        this.roomId = parts[parts.length - 1];
    }

    // Constructor without semaphore (backward compatibility)
    public ChatWebSocketClient(URI serverUri, CountDownLatch responseLatch,
                               AtomicInteger successCount, AtomicInteger errorCount) {
        this(serverUri, responseLatch, successCount, errorCount, null);
    }

    public void enableMetrics(MetricsCollector collector, String roomId) {
        this.trackMetrics = true;
        this.metricsCollector = collector;
        this.assignedRoomId = roomId;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        // Connection established
    }

    @Override
    public void onMessage(String message) {
        long receiveTime = System.currentTimeMillis();

        try {
            var response = objectMapper.readTree(message);
            String status = response.get("status").asText();

            if ("SUCCESS".equals(status)) {
                successCount.incrementAndGet();

                if (trackMetrics && metricsCollector != null) {
                    var original = response.get("originalMessage");
                    String messageType = original.get("messageType").asText();
                    metricsCollector.recordMetric(lastSendTime, receiveTime,
                            messageType, 200, assignedRoomId);
                }
            } else {
                errorCount.incrementAndGet();

                if (trackMetrics && metricsCollector != null) {
                    metricsCollector.recordMetric(lastSendTime, receiveTime,
                            "ERROR", 400, assignedRoomId);
                }
            }

        } catch (Exception e) {
            errorCount.incrementAndGet();
        } finally {
            responseLatch.countDown();

            if (inFlightSemaphore != null) {
                inFlightSemaphore.release();
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        // Connection closed
    }

    @Override
    public void onError(Exception ex) {
        log.error("WebSocket error in room {}: {}", roomId, ex.getMessage());
    }

    public void sendMessageWithTracking(String jsonMessage) {
        this.lastSendTime = System.currentTimeMillis();
        send(jsonMessage);
    }
}