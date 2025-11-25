package com.chatflow.server.persistence;

import com.chatflow.server.model.QueueMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles all database persistence operations for chat messages and user
 * activity tracking.
 * 
 * This service uses prepared statements and batch operations to achieve high
 * throughput
 * while maintaining data integrity. All operations are idempotent to handle
 * duplicate messages
 * gracefully during retries.
 * 
 * @see BatchMessageWriter for the async batch processing layer
 */
@Service
@Slf4j
public class MessagePersistenceService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Tracks total count of successfully inserted messages (excludes duplicates)
     */
    private final AtomicLong messagesInserted = new AtomicLong(0);

    /** Counts database write errors for monitoring and alerting */
    private final AtomicLong insertErrors = new AtomicLong(0);

    /** Tracks user activity table upsert operations */
    private final AtomicLong userActivityUpdates = new AtomicLong(0);

    /**
     * SQL statement for inserting messages with conflict handling.
     * Uses ON CONFLICT to ignore duplicate message_id values, making inserts
     * idempotent.
     * The ?::inet cast lets PostgreSQL handle IP address type conversion.
     */
    private static final String INSERT_MESSAGE = "INSERT INTO messages (message_id, room_id, user_id, username, " +
            "message_text, message_type, server_id, client_ip, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?::inet, ?) " +
            "ON CONFLICT (message_id) DO NOTHING";

    private static final String UPSERT_USER_ACTIVITY = "INSERT INTO user_activity (user_id, room_id, last_activity, message_count, first_activity) "
            +
            "VALUES (?, ?, ?, 1, ?) " +
            "ON CONFLICT (user_id, room_id) DO UPDATE SET " +
            "last_activity = EXCLUDED.last_activity, " +
            "message_count = user_activity.message_count + 1";

    /**
     * Batch insert messages into the database using prepared statements.
     * 
     * This is the main write method that handles bulk message persistence. It uses
     * JDBC batch operations with prepared statements for optimal performance and
     * security.
     * Duplicate messages (based on message_id) are silently skipped.
     * 
     * @param messages batch of messages to persist
     * @return array where each value indicates: 1 for inserted, 0 for
     *         duplicate/skipped
     * @throws RuntimeException if database operation fails (caller should handle
     *                          retry logic)
     */
    public int[] batchInsertMessages(List<QueueMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            log.warn("batchInsertMessages called with empty message list");
            return new int[0];
        }

        log.debug("Batch inserting {} messages", messages.size());

        try {
            int[] results = jdbcTemplate.batchUpdate(INSERT_MESSAGE, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    QueueMessage msg = messages.get(i);

                    ps.setString(1, msg.getMessageId());
                    ps.setString(2, msg.getRoomId());
                    ps.setString(3, msg.getUserId());
                    ps.setString(4, msg.getUsername());
                    ps.setString(5, msg.getMessage());
                    ps.setString(6, msg.getMessageType().toString());
                    ps.setString(7, msg.getServerId());
                    ps.setString(8, msg.getClientIp());
                    ps.setTimestamp(9, Timestamp.from(msg.getTimestampAsInstant()));
                }

                @Override
                public int getBatchSize() {
                    return messages.size();
                }
            });

            int inserted = 0;
            for (int result : results) {
                if (result > 0) {
                    inserted++;
                }
            }

            messagesInserted.addAndGet(inserted);
            log.debug("Batch insert complete: {} inserted, {} duplicates skipped",
                    inserted, messages.size() - inserted);

            return results;

        } catch (Exception e) {
            insertErrors.incrementAndGet();
            log.error("Batch insert failed for {} messages: {}", messages.size(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Updates the user activity table with a single message's metadata.
     * 
     * This method maintains the user_activity table, which tracks which users have
     * participated in which rooms. It uses PostgreSQL's INSERT ... ON CONFLICT to
     * either
     * create a new record or update an existing one with the latest activity
     * timestamp.
     * 
     * @param message the message containing user and room information
     */
    public void upsertUserActivity(QueueMessage message) {
        try {
            Timestamp now = Timestamp.from(message.getTimestampAsInstant());

            int updated = jdbcTemplate.update(UPSERT_USER_ACTIVITY,
                    message.getUserId(),
                    message.getRoomId(),
                    now,
                    now);

            if (updated > 0) {
                userActivityUpdates.incrementAndGet();
            }

        } catch (Exception e) {
            log.error("Failed to upsert user activity for user={}, room={}: {}",
                    message.getUserId(), message.getRoomId(), e.getMessage(), e);
        }
    }

    /**
     * Batch update user activity table for multiple messages at once.
     * 
     * This is more efficient than individual upserts when processing batches of
     * messages.
     * The method deduplicates (user_id, room_id) pairs within the batch and uses a
     * sorted
     * map to ensure deterministic lock ordering and prevent database deadlocks.
     * 
     * @param messages batch of messages to process for user activity updates
     */
    public void batchUpsertUserActivity(List<QueueMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        try {
            // Deduplicate and sort by (user_id, room_id) to prevent deadlocks.
            // TreeMap ensures consistent lock ordering across concurrent batches.
            java.util.Map<String, QueueMessage> dedupedMap = new java.util.TreeMap<>();

            for (QueueMessage msg : messages) {
                String key = msg.getUserId() + ":" + msg.getRoomId();
                QueueMessage existing = dedupedMap.get(key);

                if (existing == null ||
                        msg.getTimestampAsInstant().isAfter(existing.getTimestampAsInstant())) {
                    dedupedMap.put(key, msg);
                }
            }

            List<QueueMessage> dedupedMessages = new java.util.ArrayList<>(dedupedMap.values());

            if (dedupedMessages.size() < messages.size()) {
                log.debug("Deduped user activity: {} original -> {} unique (user_id, room_id) pairs",
                        messages.size(), dedupedMessages.size());
            }

            int[] results = jdbcTemplate.batchUpdate(UPSERT_USER_ACTIVITY,
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            QueueMessage msg = dedupedMessages.get(i);
                            Timestamp now = Timestamp.from(msg.getTimestampAsInstant());

                            ps.setString(1, msg.getUserId());
                            ps.setString(2, msg.getRoomId());
                            ps.setTimestamp(3, now);
                            ps.setTimestamp(4, now);
                        }

                        @Override
                        public int getBatchSize() {
                            return dedupedMessages.size();
                        }
                    });

            int updated = 0;
            for (int result : results) {
                if (result > 0)
                    updated++;
            }

            userActivityUpdates.addAndGet(updated);
            log.debug("Batch upserted {} user activity records", updated);

        } catch (Exception e) {
            log.error("Batch upsert user activity failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns the total count of messages successfully inserted into the database.
     * This count excludes duplicate messages that were skipped.
     * 
     * @return total inserted message count
     */

    public long getMessagesInserted() {
        return messagesInserted.get();
    }

    /**
     * Returns the total count of database write errors encountered.
     * Used for monitoring and alerting on persistence failures.
     * 
     * @return total error count
     */
    public long getInsertErrors() {
        return insertErrors.get();
    }

    /**
     * Returns the total count of user activity upsert operations performed.
     * 
     * @return total user activity update count
     */
    public long getUserActivityUpdates() {
        return userActivityUpdates.get();
    }

    /**
     * Tests database connectivity by executing a simple query.
     * Used by health check endpoints to verify the database is reachable.
     * 
     * @return true if database is reachable and responsive, false otherwise
     */
    public boolean testConnection() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return result != null && result == 1;
        } catch (Exception e) {
            log.error("Database connection test failed: {}", e.getMessage());
            return false;
        }
    }
}
