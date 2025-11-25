- **DeadLetterQueueService:** Captures failed writes for recovery

### Metrics API
- **MetricsService:** Executes core queries and analytics queries
- **MetricsController:** REST endpoints for querying database stats
- Materialized views for 100-200x faster analytics queries

### Architecture

```
Client → ALB → Server (consumer-v3)
                  ↓
              SQS (20 FIFO Queues)
                  ↓
          Batch Message Writer
                  ↓
          Aurora PostgreSQL
```

## Key Configuration

See `src/main/resources/application.properties`:

- **Batch Writer:** 1,000 messages per batch, 1-second flush interval
- **Database Pool:** 20 connections (HikariCP)
- **SQS Consumers:** 40 threads across 20 queues
- **Critical Optimization:** `reWriteBatchedInserts=true` for 2-3x faster writes

## Building and Running

```bash
# Build
mvn clean package -DskipTests

# Run locally
java -jar target/chatflow-server-1.0-SNAPSHOT.jar

# Deploy to EC2 (see /deployment folder)
```

## API Endpoints

### Health and Status
- `GET /health` - Server health check
- `GET /actuator/health` - Spring Boot health endpoint

### Metrics
- `GET /api/metrics/all?testStartTime={start}&testEndTime={end}` - All metrics with core queries and analytics
- `GET /api/metrics/db-stats` - Database statistics
- `POST /api/metrics/refresh` - Refresh materialized views

### WebSocket
- `ws://{host}/chat/{roomId}` - WebSocket connection for room

## Performance

### Write Performance
- **Sustained Throughput:** 3,465 msg/sec
- **Batch Write Latency:** 12ms average
- **Database CPU:** 37% peak during 500K test

### Query Performance
All core queries met their targets:
- Room messages: 42ms (target < 100ms) ✅
- User history: 118ms (target < 200ms) ✅
- Active users: 287ms (target < 500ms) ✅
- User rooms: 21ms (target < 50ms) ✅

## Monitoring

Use the scripts in `/monitoring` to track performance:
```bash
# Database metrics
cd ../monitoring
./database-monitoring.sh

# Batch writer metrics
./batch-writer-monitoring.sh
```

## Database Schema

See `/database/schema/` for:
- `01_tables.sql` - Core tables (messages, user_activity)
- `02_indexes.sql` - Performance indexes
- `03_materialized_views.sql` - Analytics views

## Troubleshooting

### Database Not Persisting
1. Check `application.properties` has correct database credentials
2. Verify Aurora cluster is running and accessible
3. Check server logs for `MessagePersistenceService` initialization

### High Latency
1. Verify `reWriteBatchedInserts=true` in datasource URL
2. Check CloudWatch for database CPU/IOPS
3. Monitor buffer depth with batch-writer-monitoring.sh

### Deadlocks
Fixed in latest version by sorting keys deterministically in batch updates.
