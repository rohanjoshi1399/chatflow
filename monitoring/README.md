# Monitoring Scripts

This directory contains monitoring scripts for tracking system performance during load testing.

## Available Scripts

### 1. queue-monitoring.sh
Monitors SQS queue depths and consumer performance across all 20 FIFO queues.

**Usage:**
```bash
./queue-monitoring.sh
```

**What it monitors:**
- Messages available in each queue
- Messages in flight
- Consumer lag per queue
- Total system throughput

### 2. database-monitoring.sh
Monitors Aurora PostgreSQL database performance and health.

**Usage:**
```bash
export DB_PASSWORD="your-db-password"
./database-monitoring.sh
```

**What it monitors:**
- Application-level database metrics via REST API
- CloudWatch metrics (CPU, connections, IOPS, latency)
- Table statistics (size, inserts, updates, live rows)
- Active database queries

**Requirements:**
- AWS CLI configured with appropriate credentials
- `jq` for JSON parsing
- PostgreSQL client (`psql`) for direct queries

### 3. batch-writer-monitoring.sh
Monitors the batch writer performance and buffer health.

**Usage:**
```bash
./batch-writer-monitoring.sh
```

**What it monitors:**
- Messages enqueued vs written
- Batch write count and errors
- Real-time write throughput
- Buffer utilization and health warnings

## Setting Up Monitoring

### Environment Variables

```bash
# Database monitoring
export DB_HOST="your-aurora-endpoint.rds.amazonaws.com"
export DB_NAME="chatdb"
export DB_USER="db_admin"
export DB_PASSWORD="your-password"
export AWS_REGION="us-west-2"

# Application endpoints
export METRICS_ENDPOINT="http://your-alb-endpoint/api/metrics"
```

### Continuous Monitoring

To run continuous monitoring during load tests:

```bash
# Monitor queues every 10 seconds
watch -n 10 ./queue-monitoring.sh

# Monitor database every 30 seconds
watch -n 30 ./database-monitoring.sh

# Monitor batch writer every 5 seconds
watch -n 5 ./batch-writer-monitoring.sh
```

## Interpreting Results

### Queue Health
- **Healthy:** Queue depths near zero, even distribution across queues
- **Warning:** Queue depths growing, indicating consumer lag
- **Critical:** Queue depths > 1000, consumers may be overwhelmed

### Database Health
- **Healthy:** CPU < 60%, write latency < 20ms, connections < 50% of pool
- **Warning:** CPU 60-80%, write latency 20-50ms
- **Critical:** CPU > 80%, write latency > 50ms, connection pool near capacity

### Batch Writer Health
- **Healthy:** Buffer < 50%, no dropped messages, write errors = 0
- **Warning:** Buffer 50-80%, occasional dropped messages
- **Critical:** Buffer > 80%, frequent drops, increasing write errors

## Troubleshooting

### Script Permissions
If you get permission denied:
```bash
chmod +x *.sh
```

### AWS CLI Issues
Ensure your AWS credentials are configured:
```bash
aws configure
```

### Database Connection Issues
Check security group rules allow connections from your IP:
```bash
psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c "SELECT 1"
```
