# Assignment 3: Consumer-v3 Integration Guide

## Overview

This guide explains how to integrate the batch writer into your SQS consumer for Assignment 3.

## Integration Steps

### 1. Modify SQSConsumerService

Add the BatchMessageWriter to your consumer:

```java
@Service
@Slf4j
public class SQSConsumerService {
    
    @Autowired
    private BatchMessageWriter batchWriter;  // ADD THIS
    
    // ... existing code ...
    
    private void processMessage(Message message, String queueUrl, String roomId, int threadNum) {
        try {
            QueueMessage queueMessage = objectMapper.readValue(
                    message.body(), 
                    QueueMessage.class
            );

            log.debug("Thread {}: Processing message {} from room {}", 
                    threadNum, queueMessage.getMessageId(), roomId);

            // Broadcast to WebSocket clients (existing)
            RoomManager.BroadcastResult result = roomManager.broadcastToRoom(queueMessage);

            // NEW: Enqueue for database persistence
            boolean enqueued = batchWriter.enqueue(queueMessage);
            if (!enqueued) {
                log.warn("Failed to enqueue message {} for persistence (buffer full)", 
                        queueMessage.getMessageId());
            }

            // Delete from SQS if broadcast succeeded
            if (result.getSuccessCount() > 0) {
                deleteMessage(queueUrl, message.receiptHandle());
                messagesProcessed.incrementAndGet();
                
                if (result.getFailureCount() > 0) {
                    log.debug("Thread {}: Message {} partially broadcast: {} successes, {} failures",
                            threadNum, queueMessage.getMessageId(), 
                            result.getSuccessCount(), result.getFailureCount());
                }
            } else {
                log.warn("Thread {}: Broadcast failed for message {} in room {}", 
                        threadNum, queueMessage.getMessageId(), roomId);
                messagesFailed.incrementAndGet();
            }

        } catch (Exception e) {
            log.error("Thread {}: Failed to process message from room {}: {}", 
                    threadNum, roomId, e.getMessage(), e);
            messagesFailed.incrementAndGet();
        }
    }
}
```

### 2. Update HealthController

Add batch writer metrics:

```java
@GetMapping("/db-metrics")
public ResponseEntity<Map<String, Object>> getDatabaseMetrics() {
    Map<String, Object> metrics = new HashMap<>();
    
    metrics.put("messages_enqueued", batchWriter.getMessagesEnqueued());
    metrics.put("messages_written", batchWriter.getMessagesWritten());
    metrics.put("batches_written", batchWriter.getBatchesWritten());
    metrics.put("dropped_messages", batchWriter.getDroppedMessages());
    metrics.put("write_errors", batchWriter.getWriteErrors());
    metrics.put("buffer_size", batchWriter.getBufferSize());
    metrics.put("batch_size_config", batchWriter.getBatchSize());
    metrics.put("flush_interval_ms", batchWriter.getFlushIntervalMs());
    
    return ResponseEntity.ok(metrics);
}
```

### 3. Configuration

Set batch parameters in `application.properties`:

```properties
# Test different configurations
batch.writer.size=1000
batch.writer.flush.interval.ms=1000
batch.writer.buffer.capacity=10000
```

## Testing

### Batch Size Optimization

Test these 5 configurations:

1. **Config 1**: `batch.writer.size=100`, `flush.interval.ms=100` (low latency)
2. **Config 2**: `batch.writer.size=500`, `flush.interval.ms=500` (balanced)
3. **Config 3**: `batch.writer.size=1000`, `flush.interval.ms=1000` (RECOMMENDED)
4. **Config 4**: `batch.writer.size=5000`, `flush.interval.ms=1000` (high throughput)
5. **Config 5**: Custom based on results

### Load Tests

Run these tests with each configuration:

- **Test 1**: 500,000 messages (baseline)
- **Test 2**: 1,000,000 messages (stress)
- **Test 3**: 30+ minutes sustained load (endurance)

### Metrics Collection

After each test:

1. Call `/api/metrics/all?testStartTime=XXX&testEndTime=YYY`
2. Log the JSON response
3. Screenshot the output
4. Document throughput, latency, and errors

## Expected Performance

With optimal configuration (1000/1000ms):

- **Write Throughput**: 8,000-12,000 messages/second
- **Batch Write Latency**: 50-150ms per batch
- **Database CPU**: 30-50%
- **Buffer Usage**: <5,000 messages

## Troubleshooting

### Buffer Full (Dropped Messages)

- Increase `batch.writer.buffer.capacity`
- Increase batch size
- Add more database writer threads

### High Latency

- Decrease batch size
- Decrease flush interval
- Check database connection pool

### Write Errors

- Check database connectivity
- Verify schema is created
- Check PostgreSQL logs
