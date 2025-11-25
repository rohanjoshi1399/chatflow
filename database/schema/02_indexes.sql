-- ChatFlow Database Schema - Indexes
-- Purpose: Optimize core queries and analytics
-- Trade-off: ~15-20% write overhead for 10-100x read speedup

-- ============================================================================
-- Messages Table Indexes
-- ============================================================================

-- Index for Query 1: Get messages for room in time range
-- Expected selectivity: High (~5% per room)
-- Used for: Room-specific message retrieval with time filtering
CREATE INDEX IF NOT EXISTS idx_room_time ON messages(room_id, created_at DESC);

-- Index for Query 2: Get user's message history
-- Expected selectivity: Medium (varies by user activity)
-- Used for: User-specific message retrieval across all rooms
CREATE INDEX IF NOT EXISTS idx_user_time ON messages(user_id, created_at DESC);

-- Index for Query 3: Count active users in time window
-- Expected selectivity: Medium-Low
-- Used for: Time-range queries across all rooms
CREATE INDEX IF NOT EXISTS idx_created_at ON messages(created_at DESC);

-- Partial index for JOIN/LEAVE analytics
-- Only indexes ~10% of rows (JOIN/LEAVE events)
-- Significantly reduces index size and maintenance cost
CREATE INDEX IF NOT EXISTS idx_message_type ON messages(message_type) 
WHERE message_type IN ('JOIN', 'LEAVE');

-- ============================================================================
-- User Activity Table Indexes
-- ============================================================================

-- Index for Query 4: Get rooms user has participated in
-- Expected selectivity: High
-- Used for: Retrieving user's room participation history
CREATE INDEX IF NOT EXISTS idx_user_last_activity ON user_activity(user_id, last_activity DESC);

-- Index for room-based activity queries
-- Used for: Finding active users in a specific room
CREATE INDEX IF NOT EXISTS idx_room_activity ON user_activity(room_id, last_activity DESC);

-- ============================================================================
-- Message Stats Minute Table Indexes
-- ============================================================================

-- Index for time-series analytics
-- Used for: Retrieving stats over time ranges
CREATE INDEX IF NOT EXISTS idx_time_bucket ON message_stats_minute(time_bucket DESC);

-- Partial index for room-specific stats
-- NULL room_id represents global stats
CREATE INDEX IF NOT EXISTS idx_room_time_bucket ON message_stats_minute(room_id, time_bucket DESC) 
WHERE room_id IS NOT NULL;

-- ============================================================================
-- Index Maintenance Notes
-- ============================================================================
-- Run ANALYZE after bulk loads:
--   ANALYZE messages;
--   ANALYZE user_activity;
--   ANALYZE message_stats_minute;
--
-- Monitor index usage:
--   SELECT schemaname, tablename, indexname, idx_scan
--   FROM pg_stat_user_indexes
--   ORDER BY idx_scan;
