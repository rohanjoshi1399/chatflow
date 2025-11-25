-- ChatFlow Database Schema - Core Tables
-- Database: Aurora PostgreSQL 15.4+
-- Author: ChatFlow Team
-- Purpose: Store chat messages, user activity, and statistics

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================================
-- Table: messages
-- Purpose: Primary storage for all chat messages
-- Expected volume: Millions of records
-- ============================================================================
CREATE TABLE IF NOT EXISTS messages (
    message_id VARCHAR(36) PRIMARY KEY,
    room_id VARCHAR(10) NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    username VARCHAR(20) NOT NULL,
    message_text VARCHAR(500) NOT NULL,
    message_type VARCHAR(10) NOT NULL CHECK (message_type IN ('TEXT', 'JOIN', 'LEAVE')),
    server_id VARCHAR(20) NOT NULL,
    client_ip INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE messages IS 'Primary storage for all chat messages';
COMMENT ON COLUMN messages.message_id IS 'Unique message identifier (UUID)';
COMMENT ON COLUMN messages.room_id IS 'Room identifier (1-20)';
COMMENT ON COLUMN messages.created_at IS 'Message timestamp with timezone';
COMMENT ON COLUMN messages.client_ip IS 'Client IP address (INET type)';

-- ============================================================================
-- Table: user_activity
-- Purpose: Track user participation per room for fast lookups
-- Updated by: Consumer-v3 via UPSERT
-- ============================================================================
CREATE TABLE IF NOT EXISTS user_activity (
    user_id VARCHAR(50) NOT NULL,
    room_id VARCHAR(10) NOT NULL,
    last_activity TIMESTAMPTZ NOT NULL,
    message_count INT NOT NULL DEFAULT 1,
    first_activity TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, room_id)
);

COMMENT ON TABLE user_activity IS 'Aggregated user activity per room';
COMMENT ON COLUMN user_activity.message_count IS 'Total messages sent by user in this room';

-- ============================================================================
-- Table: message_stats_minute
-- Purpose: Pre-aggregated time-series statistics for analytics
-- Updated by: Stats aggregator in consumer-v3
-- ============================================================================
CREATE TABLE IF NOT EXISTS message_stats_minute (
    stat_id BIGSERIAL PRIMARY KEY,
    time_bucket TIMESTAMPTZ NOT NULL,
    room_id VARCHAR(10),
    message_count INT NOT NULL,
    unique_users INT NOT NULL,
    UNIQUE (room_id, time_bucket)
);

COMMENT ON TABLE message_stats_minute IS 'Per-minute statistics for messages';
COMMENT ON COLUMN message_stats_minute.time_bucket IS 'Start of the minute bucket';
