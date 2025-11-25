package com.chatflow.server.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Handles all database query operations for metrics and analytics.
 * 
 * This service provides methods for the four core queries required in the
 * assignment,
 * plus analytics queries that leverage materialized views for fast aggregation.
 * All queries use prepared statements for security and performance.
 * 
 */
@Service
@Slf4j
public class MetricsService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${metrics.query.max.results:1000}")
    private int maxQueryResults;

    /**
     * Query 1: Retrieves messages from a specific room within a time range.
     * 
     * Returns the most recent messages first, limited to prevent overwhelming
     * clients.
     * Performance target is under 100ms for 1000 messages using the idx_room_time
     * index.
     * 
     * @param roomId    the ID of the room to query
     * @param startTime beginning of time range (inclusive)
     * @param endTime   end of time range (inclusive)
     * @return query results with execution time and sample data
     */
    public QueryResult getMessagesForRoom(String roomId, Instant startTime, Instant endTime) {
        long start = System.currentTimeMillis();

        String sql = "SELECT message_id, user_id, username, message_text, message_type, created_at " +
                "FROM messages " +
                "WHERE room_id = ? AND created_at BETWEEN ? AND ? " +
                "ORDER BY created_at DESC LIMIT ?";

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql,
                roomId,
                Timestamp.from(startTime),
                Timestamp.from(endTime),
                maxQueryResults);

        long duration = System.currentTimeMillis() - start;
        log.info("Query 1 (room messages) executed in {}ms, returned {} results", duration, results.size());

        return new QueryResult(duration, results.size(),
                results.isEmpty() ? null : results.subList(0, Math.min(5, results.size())));
    }

    /**
     * Query 2: Retrieves all messages sent by a specific user in a time range.
     * 
     * Useful for displaying a user's message history across all rooms they've
     * participated in.
     * Performance target is under 200ms, using the idx_user_time index.
     * 
     * @param userId    the ID of the user
     * @param startTime beginning of time range (inclusive)
     * @param endTime   end of time range (inclusive)
     * @return query results with execution time and sample data
     */
    public QueryResult getUserMessageHistory(String userId, Instant startTime, Instant endTime) {
        long start = System.currentTimeMillis();

        String sql = "SELECT message_id, room_id, message_text, message_type, created_at " +
                "FROM messages " +
                "WHERE user_id = ? AND created_at BETWEEN ? AND ? " +
                "ORDER BY created_at DESC";

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql,
                userId,
                Timestamp.from(startTime),
                Timestamp.from(endTime));

        long duration = System.currentTimeMillis() - start;
        log.info("Query 2 (user history) executed in {}ms, returned {} results", duration, results.size());

        return new QueryResult(duration, results.size(),
                results.isEmpty() ? null : results.subList(0, Math.min(5, results.size())));
    }

    /**
     * Query 3: Counts unique users who sent messages in a time window.
     * 
     * This gives us a sense of how many active users the system has during
     * a given period. Performance target is under 500ms using idx_created_at.
     * 
     * @param startTime beginning of time window (inclusive)
     * @param endTime   end of time window (inclusive)
     * @return query results with the count of unique active users
     */
    public QueryResult countActiveUsers(Instant startTime, Instant endTime) {
        long start = System.currentTimeMillis();

        String sql = "SELECT COUNT(DISTINCT user_id) as active_users FROM messages " +
                "WHERE created_at BETWEEN ? AND ?";

        Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                Timestamp.from(startTime),
                Timestamp.from(endTime));

        long duration = System.currentTimeMillis() - start;
        int activeUsers = (count != null) ? count : 0;
        log.info("Query 3 (active users) executed in {}ms, count={}", duration, activeUsers);

        return new QueryResult(duration, 1, Map.of("active_users", activeUsers));
    }

    /**
     * Query 4: Gets all rooms a user has participated in.
     * 
     * Uses the user_activity table for fast lookups. This table is maintained
     * by the batch writer and tracks which users have sent messages in which rooms.
     * Performance target is under 50ms.
     * 
     * @param userId the ID of the user
     * @return query results with list of rooms and participation details
     */
    public QueryResult getUserRooms(String userId) {
        long start = System.currentTimeMillis();

        String sql = "SELECT room_id, last_activity, message_count FROM user_activity " +
                "WHERE user_id = ? ORDER BY last_activity DESC";

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, userId);

        long duration = System.currentTimeMillis() - start;
        log.info("Query 4 (user rooms) executed in {}ms, returned {} rooms", duration, results.size());

        return new QueryResult(duration, results.size(), results);
    }

    /**
     * Calculates throughput statistics for the test period.
     * 
     * Returns total messages, duration, average messages per second, and peak
     * throughput.
     * The query uses explicit timestamp casts to avoid PostgreSQL operator
     * ambiguity.
     * 
     * @param startTime beginning of measurement period
     * @param endTime   end of measurement period
     * @return map containing throughput statistics
     */
    public Map<String, Object> getMessagesPerSecond(Instant startTime, Instant endTime) {
        // Optimized query using efficient aggregation instead of window functions
        String sql = "SELECT " +
                "  COUNT(*) as total_messages, " +
                "  EXTRACT(EPOCH FROM (?::timestamp - ?::timestamp)) as duration_seconds, " +
                "  CASE " +
                "    WHEN EXTRACT(EPOCH FROM (?::timestamp - ?::timestamp)) > 0 " +
                "    THEN COUNT(*) / EXTRACT(EPOCH FROM (?::timestamp - ?::timestamp)) " +
                "    ELSE 0 " +
                "  END as avg_msgs_per_second, " +
                "  ( " +
                "    SELECT COUNT(*) " +
                "    FROM messages " +
                "    WHERE created_at BETWEEN ? AND ? " +
                "    GROUP BY date_trunc('second', created_at) " +
                "    ORDER BY COUNT(*) DESC " +
                "    LIMIT 1 " +
                "  ) as peak_msgs_per_second " +
                "FROM messages " +
                "WHERE created_at BETWEEN ? AND ?";

        return jdbcTemplate.queryForMap(sql,
                Timestamp.from(endTime), Timestamp.from(startTime), // duration calc
                Timestamp.from(endTime), Timestamp.from(startTime), // duration check
                Timestamp.from(endTime), Timestamp.from(startTime), // division
                Timestamp.from(startTime), Timestamp.from(endTime), // subquery
                Timestamp.from(startTime), Timestamp.from(endTime)); // main query
    }

    /**
     * Gets the most active users from the pre-computed materialized view.
     * 
     * This is much faster than computing from the messages table directly.
     * The view is refreshed after load tests to show current data.
     * 
     * @param limit maximum number of users to return
     * @return list of top users with message counts and room participation
     */
    public List<Map<String, Object>> getTopUsers(int limit) {
        String sql = "SELECT user_id, username, total_messages, rooms_participated FROM mv_top_users LIMIT ?";
        return jdbcTemplate.queryForList(sql, limit);
    }

    /**
     * Gets the most active rooms from the pre-computed materialized view.
     * 
     * Shows which rooms have the most messages and unique users.
     * 
     * @param limit maximum number of rooms to return
     * @return list of top rooms with message and user counts
     */
    public List<Map<String, Object>> getTopRooms(int limit) {
        String sql = "SELECT room_id, total_messages, unique_users FROM mv_top_rooms LIMIT ?";
        return jdbcTemplate.queryForList(sql, limit);
    }

    /**
     * Gets user participation patterns from the materialized view.
     * 
     * Shows how users interact with the system, breaking down message types
     * and room participation.
     * 
     * @param limit maximum number of users to return
     * @return list of users with detailed participation statistics
     */
    public List<Map<String, Object>> getUserParticipationPatterns(int limit) {
        String sql = "SELECT user_id, username, text_messages, join_events, leave_events, rooms FROM mv_user_participation LIMIT ?";
        return jdbcTemplate.queryForList(sql, limit);
    }

    /**
     * Refreshes all materialized views to reflect current data.
     * 
     * This should be called after load tests complete to update the analytics
     * views.
     * Uses CONCURRENTLY to avoid locking the tables during refresh.
     */
    public void refreshMaterializedViews() {
        log.info("Refreshing materialized views...");
        long start = System.currentTimeMillis();

        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_top_users");
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_top_rooms");
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_hourly_stats");
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_participation");

            long duration = System.currentTimeMillis() - start;
            log.info("Materialized views refreshed in {}ms", duration);
        } catch (Exception e) {
            log.error("Failed to refresh materialized views: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Gets overall database statistics for monitoring.
     * 
     * Returns counts of total messages, users, rooms, and active user-room pairs.
     * Useful for health checks and capacity planning.
     * 
     * @return map containing database statistics
     */
    public Map<String, Object> getDatabaseStats() {
        String sql = "SELECT " +
                "  (SELECT COUNT(*) FROM messages) as total_messages, " +
                "  (SELECT COUNT(DISTINCT user_id) FROM messages) as total_users, " +
                "  (SELECT COUNT(DISTINCT room_id) FROM messages) as total_rooms, " +
                "  (SELECT COUNT(*) FROM user_activity) as active_user_rooms";

        return jdbcTemplate.queryForMap(sql);
    }
}
