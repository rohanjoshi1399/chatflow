-- ChatFlow Database Schema - Materialized Views
-- Purpose: Pre-compute analytics for fast query responses
-- Refresh: Call REFRESH MATERIALIZED VIEW CONCURRENTLY after load tests

-- ============================================================================
-- Materialized View: Top Users
-- Purpose: Analytics Query - Most active users
-- Refresh frequency: After each load test or on-demand
-- ============================================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_top_users AS
SELECT 
    m.user_id,
    MAX(m.username) AS username,
    COUNT(*) AS total_messages,
    COUNT(DISTINCT m.room_id) AS rooms_participated,
    MIN(m.created_at) AS first_message_at,
    MAX(m.created_at) AS last_message_at,
    COUNT(CASE WHEN m.message_type = 'TEXT' THEN 1 END) AS text_messages,
    COUNT(CASE WHEN m.message_type = 'JOIN' THEN 1 END) AS join_events,
    COUNT(CASE WHEN m.message_type = 'LEAVE' THEN 1 END) AS leave_events
FROM messages m
GROUP BY m.user_id
ORDER BY total_messages DESC;

-- Indexes for materialized view
CREATE UNIQUE INDEX IF NOT EXISTS mv_top_users_user_id_idx ON mv_top_users(user_id);
CREATE INDEX IF NOT EXISTS mv_top_users_total_messages_idx ON mv_top_users(total_messages DESC);

COMMENT ON MATERIALIZED VIEW mv_top_users IS 'Most active users ranked by message count';

-- ============================================================================
-- Materialized View: Top Rooms
-- Purpose: Analytics Query - Most active rooms
-- ============================================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_top_rooms AS
SELECT 
    room_id,
    COUNT(*) AS total_messages,
    COUNT(DISTINCT user_id) AS unique_users,
    MIN(created_at) AS first_message_at,
    MAX(created_at) AS last_message_at,
    COUNT(CASE WHEN message_type = 'TEXT' THEN 1 END) AS text_messages,
    COUNT(CASE WHEN message_type = 'JOIN' THEN 1 END) AS join_events,
    COUNT(CASE WHEN message_type = 'LEAVE' THEN 1 END) AS leave_events
FROM messages
GROUP BY room_id
ORDER BY total_messages DESC;

-- Indexes for materialized view
CREATE UNIQUE INDEX IF NOT EXISTS mv_top_rooms_room_id_idx ON mv_top_rooms(room_id);
CREATE INDEX IF NOT EXISTS mv_top_rooms_total_messages_idx ON mv_top_rooms(total_messages DESC);
CREATE INDEX IF NOT EXISTS mv_top_rooms_unique_users_idx ON mv_top_rooms(unique_users DESC);

COMMENT ON MATERIALIZED VIEW mv_top_rooms IS 'Most active rooms ranked by message count';

-- ============================================================================
-- Materialized View: Hourly Statistics
-- Purpose: Time-series analytics aggregated by hour
-- ============================================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_hourly_stats AS
SELECT 
    date_trunc('hour', created_at) AS hour_bucket,
    COUNT(*) AS total_messages,
    COUNT(DISTINCT user_id) AS unique_users,
    COUNT(DISTINCT room_id) AS active_rooms,
    COUNT(CASE WHEN message_type = 'TEXT' THEN 1 END) AS text_messages,
    COUNT(CASE WHEN message_type = 'JOIN' THEN 1 END) AS join_events,
    COUNT(CASE WHEN message_type = 'LEAVE' THEN 1 END) AS leave_events
FROM messages
GROUP BY hour_bucket
ORDER BY hour_bucket DESC;

-- Indexes for materialized view
CREATE UNIQUE INDEX IF NOT EXISTS mv_hourly_stats_hour_bucket_idx ON mv_hourly_stats(hour_bucket DESC);

COMMENT ON MATERIALIZED VIEW mv_hourly_stats IS 'Hourly aggregated message statistics';

-- ============================================================================
-- Materialized View: User Participation Patterns
-- Purpose: Detailed user behavior analysis
-- Filter: Only users with 10+ messages to reduce size
-- ============================================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_user_participation AS
SELECT 
    user_id,
    MAX(username) AS username,
    COUNT(CASE WHEN message_type = 'TEXT' THEN 1 END) AS text_messages,
    COUNT(CASE WHEN message_type = 'JOIN' THEN 1 END) AS join_events,
    COUNT(CASE WHEN message_type = 'LEAVE' THEN 1 END) AS leave_events,
    ARRAY_AGG(DISTINCT room_id ORDER BY room_id) AS rooms,
    date_trunc('hour', MIN(created_at)) AS first_activity_hour,
    date_trunc('hour', MAX(created_at)) AS last_activity_hour,
    COUNT(*) AS total_messages
FROM messages
GROUP BY user_id
HAVING COUNT(*) > 10  -- Filter out low-activity users
ORDER BY text_messages DESC;

-- Indexes for materialized view
CREATE UNIQUE INDEX IF NOT EXISTS mv_user_participation_user_id_idx ON mv_user_participation(user_id);
CREATE INDEX IF NOT EXISTS mv_user_participation_text_messages_idx ON mv_user_participation(text_messages DESC);

COMMENT ON MATERIALIZED VIEW mv_user_participation IS 'User participation patterns (10+ messages)';

-- ============================================================================
-- Refresh Instructions
-- ============================================================================
-- After load test completion, refresh all views:
--
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_top_users;
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_top_rooms;
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_hourly_stats;
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_participation;
--
-- Note: CONCURRENTLY allows reads during refresh (requires UNIQUE index)
