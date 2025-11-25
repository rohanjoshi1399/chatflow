package com.chatflow.server.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API endpoints for querying metrics and analytics.
 * 
 * Provides access to the four core queries and various analytics endpoints.
 * Results include execution times and sample data for testing and verification.
 */
@RestController
@RequestMapping("/api/metrics")
@Slf4j
public class MetricsController {

    @Autowired
    private MetricsService metricsService;

    /**
     * Returns all metrics in a single response - core queries, analytics, and
     * database stats.
     * 
     * This is the main endpoint for post-test verification. It executes all four
     * core queries,
     * refreshes materialized views, and returns analytics data. The response
     * includes execution
     * times for each query to verify performance targets are met.
     * 
     * @param testStartTime ISO-8601 timestamp for start of test period
     * @param testEndTime   ISO-8601 timestamp for end of test period
     * @param sampleUserId  user ID to use for user-specific queries (default: "1")
     * @param sampleRoomId  room ID to use for room-specific queries (default: "1")
     * @return comprehensive metrics response with all query results
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAllMetrics(
            @RequestParam String testStartTime,
            @RequestParam String testEndTime,
            @RequestParam(defaultValue = "1") String sampleUserId,
            @RequestParam(defaultValue = "1") String sampleRoomId) {

        log.info("Fetching all metrics for period: {} to {}", testStartTime, testEndTime);

        // Validate room ID format (1-20)
        if (!sampleRoomId.matches("\\d{1,2}")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid roomId, must be 1-20"));
        }
        int roomNum = Integer.parseInt(sampleRoomId);
        if (roomNum < 1 || roomNum > 20) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid roomId, must be 1-20"));
        }

        // Parse timestamps with error handling
        Instant start, end;
        try {
            start = Instant.parse(testStartTime);
            end = Instant.parse(testEndTime);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid timestamp format, use ISO-8601 (e.g., 2024-01-01T00:00:00Z)"));
        }

        // Validate time range
        if (end.isBefore(start)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "testEndTime must be after testStartTime"));
        }

        Map<String, Object> response = new HashMap<>();

        // Test period info
        response.put("test_period", Map.of(
                "start_time", testStartTime,
                "end_time", testEndTime,
                "duration_seconds", end.getEpochSecond() - start.getEpochSecond()));

        try {
            metricsService.refreshMaterializedViews();
        } catch (Exception e) {
            log.warn("Failed to refresh materialized views: {}", e.getMessage());
        }

        // Core Queries
        Map<String, Object> coreQueries = new HashMap<>();
        coreQueries.put("query1_room_messages", metricsService.getMessagesForRoom(sampleRoomId, start, end));
        coreQueries.put("query2_user_history", metricsService.getUserMessageHistory(sampleUserId, start, end));
        coreQueries.put("query3_active_users", metricsService.countActiveUsers(start, end));
        coreQueries.put("query4_user_rooms", metricsService.getUserRooms(sampleUserId));
        response.put("core_queries", coreQueries);

        // Analytics
        Map<String, Object> analytics = new HashMap<>();
        analytics.put("messages_per_second", metricsService.getMessagesPerSecond(start, end));
        analytics.put("top_users", metricsService.getTopUsers(10));
        analytics.put("top_rooms", metricsService.getTopRooms(20));
        analytics.put("user_participation", metricsService.getUserParticipationPatterns(10));
        response.put("analytics", analytics);

        // Database stats
        response.put("database_stats", metricsService.getDatabaseStats());

        // Metadata
        response.put("generated_at", Instant.now().toString());

        log.info("Metrics collection complete");
        return ResponseEntity.ok(response);
    }

    /**
     * Returns only the four core queries without analytics.
     * 
     * Lighter weight than the /all endpoint if you only need to verify query
     * performance.
     * 
     * @param testStartTime ISO-8601 timestamp for start of test period
     * @param testEndTime   ISO-8601 timestamp for end of test period
     * @param sampleUserId  user ID for user-specific queries
     * @param sampleRoomId  room ID for room-specific queries
     * @return core query results with execution times
     */
    @GetMapping("/core-queries")
    public ResponseEntity<Map<String, Object>> getCoreQueries(
            @RequestParam String testStartTime,
            @RequestParam String testEndTime,
            @RequestParam(defaultValue = "user-1") String sampleUserId,
            @RequestParam(defaultValue = "1") String sampleRoomId) {

        Instant start = Instant.parse(testStartTime);
        Instant end = Instant.parse(testEndTime);

        Map<String, Object> coreQueries = new HashMap<>();
        coreQueries.put("query1_room_messages", metricsService.getMessagesForRoom(sampleRoomId, start, end));
        coreQueries.put("query2_user_history", metricsService.getUserMessageHistory(sampleUserId, start, end));
        coreQueries.put("query3_active_users", metricsService.countActiveUsers(start, end));
        coreQueries.put("query4_user_rooms", metricsService.getUserRooms(sampleUserId));

        return ResponseEntity.ok(coreQueries);
    }

    /**
     * Returns only analytics data (no core queries).
     * 
     * Includes throughput statistics, top users/rooms, and participation patterns.
     * Automatically refreshes materialized views before querying.
     * 
     * @param testStartTime ISO-8601 timestamp for start of measurement period
     * @param testEndTime   ISO-8601 timestamp for end of measurement period
     * @return analytics results from materialized views
     */
    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics(
            @RequestParam String testStartTime,
            @RequestParam String testEndTime) {

        Instant start = Instant.parse(testStartTime);
        Instant end = Instant.parse(testEndTime);

        // Refresh views
        metricsService.refreshMaterializedViews();

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("messages_per_second", metricsService.getMessagesPerSecond(start, end));
        analytics.put("top_users", metricsService.getTopUsers(10));
        analytics.put("top_rooms", metricsService.getTopRooms(20));
        analytics.put("user_participation", metricsService.getUserParticipationPatterns(10));

        return ResponseEntity.ok(analytics);
    }

    /**
     * Manually triggers a refresh of all materialized views.
     * 
     * Use this after load tests complete to ensure analytics data is current.
     * The refresh happens concurrently to avoid locking the base tables.
     * 
     * @return success or error status
     */
    @PostMapping("/refresh-views")
    public ResponseEntity<Map<String, String>> refreshViews() {
        log.info("Manual refresh of materialized views requested");

        try {
            metricsService.refreshMaterializedViews();
            return ResponseEntity.ok(Map.of("status", "success", "message", "Materialized views refreshed"));
        } catch (Exception e) {
            log.error("Failed to refresh views: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Returns basic database statistics for monitoring.
     * 
     * Includes total counts of messages, users, rooms, and active user-room pairs.
     * 
     * @return database statistics map
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(metricsService.getDatabaseStats());
    }
}
