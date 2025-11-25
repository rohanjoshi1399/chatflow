package com.chatflow.server.controller;

import com.chatflow.server.handler.ChatWebSocketHandler;
import com.chatflow.server.persistence.BatchMessageWriter;
import com.chatflow.server.persistence.MessagePersistenceService;
import com.chatflow.server.service.RoomManager;
import com.chatflow.server.service.SQSConsumerService;
import com.chatflow.server.service.SQSService;
import com.chatflow.server.service.WebSocketWriteManager;

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
    private RoomManager roomManager;

    @Autowired
    private WebSocketWriteManager writeManager;

    @Autowired(required = false)
    private BatchMessageWriter batchWriter;

    @Autowired(required = false)
    private MessagePersistenceService persistenceService;

    @Value("${server.id:server-1}")
    private String serverId;

    /**
     * Main health check endpoint for load balancer health monitoring.
     * Checks SQS connectivity, consumer status, write manager, and optionally
     * database.
     * 
     * @return health status with component-level details
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("serverId", serverId);
        health.put("timestamp", System.currentTimeMillis());

        try {
            // Check SQS connectivity
            boolean sqsHealthy = checkSQSHealth();
            health.put("sqsHealthy", sqsHealthy);

            // Check consumer running
            boolean consumerHealthy = consumerService.isRunning();
            health.put("consumerHealthy", consumerHealthy);

            // Check write manager
            boolean writeManagerHealthy = checkWriteManagerHealth();
            health.put("writeManagerHealthy", writeManagerHealthy);

            // Check database (optional - Assignment 3)
            boolean databaseHealthy = checkDatabaseHealth();
            health.put("databaseHealthy", databaseHealthy);
            health.put("databaseConfigured", persistenceService != null);

            // Overall status (database is optional, so don't fail on it)
            boolean isHealthy = sqsHealthy && consumerHealthy && writeManagerHealthy;
            String status = isHealthy ? "UP" : "DOWN";
            health.put("status", status);

            if (!isHealthy) {
                return ResponseEntity.status(503).body(health);
            }

            return ResponseEntity.ok(health);

        } catch (Exception e) {
            log.error("Health check failed with exception: {}", e.getMessage(), e);
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
            return ResponseEntity.status(503).body(health);
        }
    }

    /**
     * Returns detailed application metrics for monitoring.
     * Includes WebSocket handler stats, SQS metrics, consumer metrics, and room
     * stats.
     * 
     * @return comprehensive metrics from all system components
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        Map<String, Object> metrics = new HashMap<>();

        metrics.put("serverId", serverId);
        metrics.put("timestamp", System.currentTimeMillis());

        // WebSocket handler metrics
        Map<String, Object> handlerMetrics = new HashMap<>();
        handlerMetrics.put("messagesReceived", webSocketHandler.getMessagesReceived());
        handlerMetrics.put("messagesPublished", webSocketHandler.getMessagesPublished());
        handlerMetrics.put("messagesFailed", webSocketHandler.getMessagesFailed());
        handlerMetrics.put("acksSent", webSocketHandler.getAcksSent());
        handlerMetrics.put("acksFailed", webSocketHandler.getAcksFailed());
        metrics.put("handler", handlerMetrics);

        // SQS service metrics
        Map<String, Object> sqsMetrics = new HashMap<>();
        sqsMetrics.put("messagesSent", sqsService.getMessagesSent());
        metrics.put("sqs", sqsMetrics);

        // Consumer metrics
        Map<String, Object> consumerMetrics = new HashMap<>();
        consumerMetrics.put("messagesProcessed", consumerService.getMessagesProcessed());
        consumerMetrics.put("messagesFailed", consumerService.getMessagesFailed());
        consumerMetrics.put("messagesDeletedWithoutBroadcast", consumerService.getMessagesDeletedWithoutBroadcast());
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

        // Write manager metrics
        Map<String, Object> writeMetrics = getWriteMetrics();
        metrics.put("writeManager", writeMetrics);

        return ResponseEntity.ok(metrics);
    }

    /**
     * Returns database persistence metrics including write counts and errors.
     * Only available if database persistence is configured.
     * 
     * @return database-specific metrics
     */
    @GetMapping("/db-metrics")
    public ResponseEntity<Map<String, Object>> getDatabaseMetrics() {
        if (batchWriter == null || persistenceService == null) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Database services not configured"));
        }

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("serverId", serverId);
        metrics.put("timestamp", System.currentTimeMillis());
        // Persistence service metrics
        Map<String, Object> persistenceMetrics = new HashMap<>();
        persistenceMetrics.put("messages_inserted", persistenceService.getMessagesInserted());
        persistenceMetrics.put("insert_errors", persistenceService.getInsertErrors());
        persistenceMetrics.put("user_activity_updates", persistenceService.getUserActivityUpdates());
        metrics.put("persistence", persistenceMetrics);

        return ResponseEntity.ok(metrics);
    }

    private Map<String, Object> getWriteMetrics() {
        Map<String, Object> writeMetrics = new HashMap<>();
        writeMetrics.put("messagesSent", writeManager.getTotalMessagesSent());
        writeMetrics.put("messagesQueued", writeManager.getTotalMessagesQueued());
        writeMetrics.put("messagesDropped", writeManager.getTotalMessagesDropped());
        writeMetrics.put("writeErrors", writeManager.getTotalWriteErrors());
        writeMetrics.put("activeSessions", writeManager.getActiveSessionCount());
        writeMetrics.put("activeWriterThreads", writeManager.getActiveWriterThreadCount());
        return writeMetrics;
    }

    /**
     * Returns SQS queue depth statistics for a specific room.
     * 
     * @param roomId the room ID to check
     * @return queue statistics including visible, in-flight, and delayed message
     *         counts
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
     * Returns SQS queue statistics for all 20 rooms.
     * Useful for monitoring queue health across the entire system.
     * 
     * @return map of room statistics
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

    // Helper methods for health checks
    private boolean checkSQSHealth() {
        try {
            Map<String, String> attrs = sqsService.getQueueAttributes("1");
            return attrs != null && !attrs.isEmpty();
        } catch (Exception e) {
            log.error("SQS health check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkWriteManagerHealth() {
        try {
            long activeWriters = writeManager.getActiveWriterThreadCount();
            return activeWriters >= 0;
        } catch (Exception e) {
            log.error("Write manager health check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkDatabaseHealth() {
        if (persistenceService == null) {
            return true; // Database is optional
        }

        try {
            return persistenceService.testConnection();
        } catch (Exception e) {
            log.error("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}