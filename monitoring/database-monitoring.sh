#!/bin/bash

# Database Performance Monitoring Script
# Monitors Aurora PostgreSQL metrics and application database stats

set -e

# Configuration
DB_HOST="${DB_HOST:-your-aurora-endpoint.rds.amazonaws.com}"
DB_NAME="${DB_NAME:-chatdb}"
DB_USER="${DB_USER:-db_admin}"
REGION="${AWS_REGION:-us-west-2}"
METRICS_ENDPOINT="${METRICS_ENDPOINT:-http://localhost:8080/api/metrics/db-stats}"

echo "=========================================="
echo "Database Performance Monitor"
echo "=========================================="
echo "Database: $DB_NAME"
echo "Host: $DB_HOST"
echo "Region: $REGION"
echo ""

# Function to query database stats via metrics API
get_app_metrics() {
    echo "--- Application Database Metrics ---"
    curl -s "$METRICS_ENDPOINT" | jq '.' || echo "Failed to fetch application metrics"
    echo ""
}

# Function to get Aurora CloudWatch metrics
get_cloudwatch_metrics() {
    echo "--- Aurora CloudWatch Metrics ---"
    
    # Get database identifier from endpoint
    DB_IDENTIFIER=$(echo $DB_HOST | cut -d'.' -f1)
    
    # CPU Utilization
    aws cloudwatch get-metric-statistics \
        --namespace AWS/RDS \
        --metric-name CPUUtilization \
        --dimensions Name=DBInstanceIdentifier,Value=$DB_IDENTIFIER \
        --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
        --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
        --period 60 \
        --statistics Average,Maximum \
        --region $REGION \
        --query 'Datapoints[0].[Timestamp,Average,Maximum]' \
        --output text | awk '{printf "CPU Usage: Avg=%.2f%%, Max=%.2f%%\n", $2, $3}'
    
    # Database Connections
    aws cloudwatch get-metric-statistics \
        --namespace AWS/RDS \
        --metric-name DatabaseConnections \
        --dimensions Name=DBInstanceIdentifier,Value=$DB_IDENTIFIER \
        --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
        --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
        --period 60 \
        --statistics Average,Maximum \
        --region $REGION \
        --query 'Datapoints[0].[Timestamp,Average,Maximum]' \
        --output text | awk '{printf "DB Connections: Avg=%.0f, Max=%.0f\n", $2, $3}'
    
    # Write IOPS
    aws cloudwatch get-metric-statistics \
        --namespace AWS/RDS \
        --metric-name WriteIOPS \
        --dimensions Name=DBInstanceIdentifier,Value=$DB_IDENTIFIER \
        --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
        --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
        --period 60 \
        --statistics Average,Maximum \
        --region $REGION \
        --query 'Datapoints[0].[Timestamp,Average,Maximum]' \
        --output text | awk '{printf "Write IOPS: Avg=%.2f, Max=%.2f\n", $2, $3}'
    
    # Write Latency
    aws cloudwatch get-metric-statistics \
        --namespace AWS/RDS \
        --metric-name WriteLatency \
        --dimensions Name=DBInstanceIdentifier,Value=$DB_IDENTIFIER \
        --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
        --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
        --period 60 \
        --statistics Average,Maximum \
        --region $REGION \
        --query 'Datapoints[0].[Timestamp,Average,Maximum]' \
        --output text | awk '{printf "Write Latency: Avg=%.4fs, Max=%.4fs\n", $2, $3}'
    
    echo ""
}

# Function to query database directly for table stats
get_table_stats() {
    echo "--- Database Table Statistics ---"
    
    export PGPASSWORD="$DB_PASSWORD"
    
    psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -c "
    SELECT 
        schemaname,
        tablename,
        pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size,
        n_tup_ins AS inserts,
        n_tup_upd AS updates,
        n_tup_del AS deletes,
        n_live_tup AS live_rows
    FROM pg_stat_user_tables
    WHERE schemaname = 'public'
    ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
    " 2>/dev/null || echo "Failed to connect to database"
    
    echo ""
}

# Function to check active queries
get_active_queries() {
    echo "--- Active Database Queries ---"
    
    export PGPASSWORD="$DB_PASSWORD"
    
    psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -c "
    SELECT 
        pid,
        usename,
        application_name,
        client_addr,
        state,
        EXTRACT(EPOCH FROM (now() - query_start)) AS duration_seconds,
        LEFT(query, 100) AS query_preview
    FROM pg_stat_activity
    WHERE state != 'idle'
      AND pid != pg_backend_pid()
    ORDER BY query_start;
    " 2>/dev/null || echo "Failed to connect to database"
    
    echo ""
}

# Main execution
echo "Collecting metrics at $(date)"
echo ""

# Get application-level metrics
get_app_metrics

# Get CloudWatch metrics if AWS CLI is available
if command -v aws &> /dev/null; then
    get_cloudwatch_metrics
else
    echo "AWS CLI not found. Skipping CloudWatch metrics."
    echo ""
fi

# Get database table stats if credentials provided
if [ -n "$DB_PASSWORD" ]; then
    get_table_stats
    get_active_queries
else
    echo "DB_PASSWORD not set. Skipping direct database queries."
    echo "Set DB_PASSWORD environment variable to enable."
    echo ""
fi

echo "Monitoring complete at $(date)"
echo "=========================================="
