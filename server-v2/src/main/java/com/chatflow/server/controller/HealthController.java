package com.chatflow.server.controller;

import com.chatflow.server.handler.ChatWebSocketHandler;
import com.chatflow.server.service.RoomSessionManager;
import com.chatflow.server.service.SQSConsumerService;
import com.chatflow.server.service.SQSService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@Slf4j
public class HealthController {

    @Autowired
    private ChatWebSocketHandler webSocketHandler;

    @Autowired
    private SQSService sqsService;

    @Autowired
    private SQSConsumerService consumerService;

    @Autowired
    private RoomSessionManager roomManager;

    @Value("${server.id:server-1}")
    private String serverId;

    /**
     * Health check endpoint for ALB
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("serverId", serverId);
        health.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(health);
    }

    /**
     * Detailed metrics endpoint
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        Map<String, Object> metrics = new HashMap<>();

        // Server info
        metrics.put("serverId", serverId);
        metrics.put("timestamp", System.currentTimeMillis());

        // WebSocket handler metrics
        Map<String, Object> handlerMetrics = new HashMap<>();
        handlerMetrics.put("messagesReceived", webSocketHandler.getMessagesReceived());
        handlerMetrics.put("messagesPublished", webSocketHandler.getMessagesPublished());
        handlerMetrics.put("messagesFailed", webSocketHandler.getMessagesFailed());
        metrics.put("handler", handlerMetrics);

        // SQS service metrics
        Map<String, Object> sqsMetrics = new HashMap<>();
        sqsMetrics.put("messagesSent", sqsService.getMessagesSent());
        metrics.put("sqs", sqsMetrics);

        // Consumer metrics
        Map<String, Object> consumerMetrics = new HashMap<>();
        consumerMetrics.put("messagesProcessed", consumerService.getMessagesProcessed());
        consumerMetrics.put("messagesFailed", consumerService.getMessagesFailed());
        consumerMetrics.put("isRunning", consumerService.isRunning());
        metrics.put("consumer", consumerMetrics);

        // Room manager metrics
        Map<String, Object> roomMetrics = new HashMap<>();
        roomMetrics.put("totalSessions", roomManager.getTotalSessionCount());
        roomMetrics.put("activeRooms", roomManager.getActiveRoomCount());
        roomMetrics.put("messagesBroadcast", roomManager.getMessagesBroadcast());
        roomMetrics.put("broadcastFailures", roomManager.getBroadcastFailures());
        roomMetrics.put("roomStats", roomManager.getRoomStats());
        metrics.put("rooms", roomMetrics);

        return ResponseEntity.ok(metrics);
    }

    /**
     * Get queue depth for a specific room
     */
    @GetMapping("/queue/{roomId}")
    public ResponseEntity<Map<String, Object>> queueStats(@PathVariable String roomId) {
        try {
            Map<String, String> attributes = sqsService.getQueueAttributes(roomId);

            Map<String, Object> stats = new HashMap<>();
            stats.put("roomId", roomId);
            stats.put("queueUrl", sqsService.getCachedQueueUrl(roomId));
            stats.put("approximateMessages", attributes.getOrDefault("ApproximateNumberOfMessages", "0"));
            stats.put("approximateNotVisible", attributes.getOrDefault("ApproximateNumberOfMessagesNotVisible", "0"));
            stats.put("approximateDelayed", attributes.getOrDefault("ApproximateNumberOfMessagesDelayed", "0"));

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("Error getting queue stats for room {}: {}", roomId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all queue depths
     */
    @GetMapping("/queue/all")
    public ResponseEntity<Map<String, Object>> allQueueStats() {
        Map<String, Object> allStats = new HashMap<>();

        for (int i = 1; i <= 20; i++) {
            String roomId = String.valueOf(i);
            try {
                Map<String, String> attributes = sqsService.getQueueAttributes(roomId);

                Map<String, String> roomStats = new HashMap<>();
                roomStats.put("messages", attributes.getOrDefault("ApproximateNumberOfMessages", "0"));
                roomStats.put("notVisible", attributes.getOrDefault("ApproximateNumberOfMessagesNotVisible", "0"));
                roomStats.put("delayed", attributes.getOrDefault("ApproximateNumberOfMessagesDelayed", "0"));

                allStats.put("room" + roomId, roomStats);
            } catch (Exception e) {
                log.error("Error getting stats for room {}: {}", roomId, e.getMessage());
            }
        }

        return ResponseEntity.ok(allStats);
    }
}