#!/bin/bash

# Batch Writer Performance Monitor
# Monitors batch writer metrics and database write performance

set -e

METRICS_ENDPOINT="${METRICS_ENDPOINT:-http://localhost:8080/actuator/metrics}"
CUSTOM_METRICS_ENDPOINT="${CUSTOM_METRICS_ENDPOINT:-http://localhost:8080/api/metrics/batch-writer}"

echo "=========================================="
echo "Batch Writer Performance Monitor"
echo "=========================================="
echo ""

# Function to get batch writer metrics
get_batch_writer_metrics() {
    echo "--- Batch Writer Metrics ---"
    
    # Get custom metrics if available
    response=$(curl -s "$CUSTOM_METRICS_ENDPOINT" 2>/dev/null)
    
    if [ $? -eq 0 ]; then
        echo "$response" | jq '{
            messages_enqueued: .messagesEnqueued,
            messages_written: .messagesWritten,
            batches_written: .batchesWritten,
            dropped_messages: .droppedMessages,
            write_errors: .writeErrors,
            buffer_size: .bufferSize,
            batch_size_config: .batchSize,
            flush_interval_ms: .flushIntervalMs
        }'
    else
        echo "Failed to fetch batch writer metrics"
    fi
    
    echo ""
}

# Function to calculate throughput
calculate_throughput() {
    echo "--- Throughput Analysis ---"
    
    # Get metrics twice with 5 second interval
    metrics1=$(curl -s "$CUSTOM_METRICS_ENDPOINT" 2>/dev/null)
    sleep 5
    metrics2=$(curl -s "$CUSTOM_METRICS_ENDPOINT" 2>/dev/null)
    
    if [ $? -eq 0 ]; then
        written1=$(echo "$metrics1" | jq -r '.messagesWritten // 0')
        written2=$(echo "$metrics2" | jq -r '.messagesWritten // 0')
        
        messages_diff=$((written2 - written1))
        throughput=$(echo "scale=2; $messages_diff / 5" | bc)
        
        echo "Messages written in last 5 seconds: $messages_diff"
        echo "Write throughput: $throughput msg/sec"
    else
        echo "Failed to calculate throughput"
    fi
    
    echo ""
}

# Function to monitor buffer health
monitor_buffer_health() {
    echo "--- Buffer Health ---"
    
    response=$(curl -s "$CUSTOM_METRICS_ENDPOINT" 2>/dev/null)
    
    if [ $? -eq 0 ]; then
        buffer_size=$(echo "$response" | jq -r '.bufferSize // 0')
        buffer_capacity=10000  # From config
        
        buffer_pct=$(echo "scale=2; ($buffer_size * 100) / $buffer_capacity" | bc)
        
        echo "Current buffer depth: $buffer_size / $buffer_capacity"
        echo "Buffer utilization: $buffer_pct%"
        
        if (( $(echo "$buffer_pct > 80" | bc -l) )); then
            echo "⚠️  WARNING: Buffer is over 80% full!"
        elif (( $(echo "$buffer_pct > 50" | bc -l) )); then
            echo "⚠️  CAUTION: Buffer is over 50% full"
        else
            echo "✅ Buffer healthy"
        fi
    fi
    
    echo ""
}

# Main execution
echo "Collecting batch writer metrics at $(date)"
echo ""

get_batch_writer_metrics
calculate_throughput
monitor_buffer_health

echo "=========================================="
echo "Monitoring complete at $(date)"
