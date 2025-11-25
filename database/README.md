├── schema/
│   ├── 01_tables.sql             # Core tables (messages, user_activity, stats)
│   ├── 02_indexes.sql            # Performance indexes
│   └── 03_materialized_views.sql # Analytics views
├── queries/
│   └── core_queries.sql          # Example queries for testing
└── README.md                     # This file
```

## Prerequisites

1. **Aurora PostgreSQL Cluster** created in AWS
2. **PostgreSQL client** (`psql`) installed locally
3. **Network access** to Aurora cluster (security groups configured)

## Quick Start

### 1. Set Environment Variables

```bash
export DB_HOST="chatflow-pg-cluster.cluster-xxxxx.us-west-2.rds.amazonaws.com"
export DB_NAME="chatflow"
export DB_USER="postgres"
export DB_PASSWORD="your_password"
```

### 2. Run Setup Script

```bash
chmod +x setup.sh
./setup.sh
```

### 3. Verify Setup

```bash
psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c "\dt"  # List tables
psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c "\di"  # List indexes
psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c "\dm"  # List materialized views
```

## Schema Overview

### Core Tables

| Table | Purpose | Size Estimate |
|-------|---------|---------------|
| `messages` | Primary message storage | Millions of rows |
| `user_activity` | User-room participation | Thousands of rows |
| `message_stats_minute` | Time-series statistics | Thousands of rows |

### Materialized Views

| View | Purpose | Refresh Trigger |
|------|---------|-----------------|
| `mv_top_users` | Top users by message count | After load tests |
| `mv_top_rooms` | Top rooms by activity | After load tests |
| `mv_hourly_stats` | Hourly time-series | After load tests |
| `mv_user_participation` | User behavior patterns | After load tests |

## Indexes

All indexes are created automatically. Key indexes:

- `idx_room_time`: Room + timestamp (Query 1)
- `idx_user_time`: User + timestamp (Query 2)
- `idx_created_at`: Timestamp only (Query 3)
- Unique indexes on materialized views for CONCURRENT refresh

## Maintenance

### Refresh Materialized Views

After each load test:

```sql
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_top_users;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_top_rooms;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_hourly_stats;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_participation;
```

### Analyze Tables

After bulk inserts:

```sql
ANALYZE messages;
ANALYZE user_activity;
ANALYZE message_stats_minute;
```

### Monitor Index Usage

```sql
SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
ORDER BY idx_scan DESC;
```

## Troubleshooting

### Connection Issues

```bash
# Test connectivity
telnet $DB_HOST 5432

# Check security groups
aws rds describe-db-clusters --db-cluster-identifier chatflow-pg-cluster
```

### Slow Queries

```sql
-- Enable query logging
ALTER DATABASE chatflow SET log_statement = 'all';
ALTER DATABASE chatflow SET log_duration = on;

-- Check slow queries
SELECT * FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 10;
```

## Next Steps

1. Configure `application.properties` in consumer-v3
2. Run load tests
3. Refresh materialized views
4. Call `/api/metrics/all` endpoint
