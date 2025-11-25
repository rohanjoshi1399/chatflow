package com.chatflow.server.service;

import com.chatflow.server.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batches SQS publish operations to reduce API calls and costs.
 * 
 * Problem: Currently each message sent individually = 500K messages = 500K API calls
 * SQS supports batching up to 10 messages per SendMessageBatch call.
 * 
 * Solution: Batch messages per room, flush every 100ms or when batch reaches 10 messages.
 * 
 * Cost Savings: ~10x reduction in API calls = 90% cost savings on SendMessage
 */
@Service
@Slf4j
public class SQSBatchPublisher {

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SQSService sqsService;

    @Value("${sqs.batch.enabled:false}")
    private boolean batchEnabled;

    @Value("${sqs.batch.max.size:10}")
    private int maxBatchSize;

    @Value("${sqs.batch.flush.interval.ms:100}")
    private long flushIntervalMs;

    @Value("${sqs.fifo.enabled:true}")
    private boolean fifoEnabled;

    // Batch queue per room
    private final Map<String, BlockingQueue<QueueMessage>> batchQueues = new ConcurrentHashMap<>();
    
    // Scheduled flusher
    private ScheduledExecutorService flushScheduler;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Metrics
    private final AtomicLong messagesQueued = new AtomicLong(0);
    private final AtomicLong batchesSent = new AtomicLong(0);
    private final AtomicLong messagesInBatches = new AtomicLong(0);
    private final AtomicLong batchesFailed = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);

    @PostConstruct
    public void init() {
        if (!batchEnabled) {
            log.info("SQS Batch Publishing is disabled");
            return;
        }

        log.info("Initializing SQS Batch Publisher");
        log.info("  Max batch size: {}", maxBatchSize);
        log.info("  Flush interval: {}ms", flushIntervalMs);
        
        // Create batch queue for each room
        for (int i = 1; i <= 20; i++) {
            String roomId = String.valueOf(i);
            batchQueues.put(roomId, new LinkedBlockingQueue<>(100));
        }
        
        // Start flush scheduler
        flushScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "sqs-batch-flusher");
            t.setDaemon(false);
            return t;
        });
        
        running.set(true);
        
        flushScheduler.scheduleAtFixedRate(
                this::flushAllBatches,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
        );
        
        log.info("SQS Batch Publisher initialized successfully");
    }

    /**
     * Queue a message for batched publishing
     * 
     * @return true if queued successfully, false if queue full
     */
    public boolean publishMessage(QueueMessage message) {
        if (!batchEnabled) {
            log.error("Batch publishing not enabled but publishMessage called!");
            return false;
        }

        BlockingQueue<QueueMessage> queue = batchQueues.get(message.getRoomId());
        
        if (queue == null) {
            log.error("No batch queue for room: {}", message.getRoomId());
            return false;
        }
        
        boolean queued = queue.offer(message);
        
        if (queued) {
            messagesQueued.incrementAndGet();
            
            // If queue reaches batch size, flush immediately
            if (queue.size() >= maxBatchSize) {
                flushBatchForRoom(message.getRoomId());
            }
        } else {
            log.warn("Batch queue full for room {}, dropping message", message.getRoomId());
            messagesDropped.incrementAndGet();
        }
        
        return queued;
    }

    /**
     * Flush all batch queues (called by scheduler)
     */
    private void flushAllBatches() {
        if (!running.get()) {
            return;
        }

        for (String roomId : batchQueues.keySet()) {
            try {
                flushBatchForRoom(roomId);
            } catch (Exception e) {
                log.error("Error flushing batch for room {}: {}", roomId, e.getMessage());
            }
        }
    }

    /**
     * Flush batch queue for a specific room
     */
    private void flushBatchForRoom(String roomId) {
        BlockingQueue<QueueMessage> queue = batchQueues.get(roomId);
        
        if (queue == null || queue.isEmpty()) {
            return;
        }
        
        // Drain up to maxBatchSize messages
        List<QueueMessage> batch = new ArrayList<>(maxBatchSize);
        queue.drainTo(batch, maxBatchSize);
        
        if (batch.isEmpty()) {
            return;
        }
        
        // Send batch to SQS
        sendBatch(roomId, batch);
    }

    /**
     * Send a batch of messages to SQS
     */
    private void sendBatch(String roomId, List<QueueMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }

        try {
            String queueUrl = sqsService.getCachedQueueUrl(roomId);
            
            if (queueUrl == null) {
                log.error("Queue URL not available for room {}, dropping {} messages", 
                        roomId, messages.size());
                messagesDropped.addAndGet(messages.size());
                return;
            }

            // Build batch request entries
            List<SendMessageBatchRequestEntry> entries = new ArrayList<>();
            
            for (QueueMessage msg : messages) {
                try {
                    String messageBody = objectMapper.writeValueAsString(msg);
                    
                    SendMessageBatchRequestEntry.Builder entryBuilder = SendMessageBatchRequestEntry.builder()
                            .id(msg.getMessageId())
                            .messageBody(messageBody);
                    
                    // Add FIFO-specific attributes
                    if (fifoEnabled) {
                        entryBuilder
                                .messageGroupId(roomId)
                                .messageDeduplicationId(msg.getMessageId());
                    }
                    
                    entries.add(entryBuilder.build());
                    
                } catch (Exception e) {
                    log.error("Failed to serialize message {}: {}", msg.getMessageId(), e.getMessage());
                    messagesDropped.incrementAndGet();
                }
            }

            if (entries.isEmpty()) {
                return;
            }

            // Send batch
            SendMessageBatchRequest batchRequest = SendMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build();

            SendMessageBatchResponse response = sqsClient.sendMessageBatch(batchRequest);

            // Track metrics
            batchesSent.incrementAndGet();
            messagesInBatches.addAndGet(entries.size());

            // Handle partial failures
            if (response.hasFailed()) {
                List<BatchResultErrorEntry> failures = response.failed();
                log.warn("Batch send to room {} had {} failures out of {}", 
                        roomId, failures.size(), entries.size());
                
                for (BatchResultErrorEntry error : failures) {
                    log.error("Message {} failed: {} - {}", 
                            error.id(), error.code(), error.message());
                }
                
                batchesFailed.incrementAndGet();
                messagesDropped.addAndGet(failures.size());
            }

            // Log successful sends
            int successCount = response.successful().size();
            log.debug("Successfully sent batch to room {}: {} messages", roomId, successCount);

        } catch (Exception e) {
            log.error("Failed to send batch to room {}: {}", roomId, e.getMessage(), e);
            batchesFailed.incrementAndGet();
            messagesDropped.addAndGet(messages.size());
        }
    }

    /**
     * Graceful shutdown - flush all pending batches
     */
    @PreDestroy
    public void shutdown() {
        if (!batchEnabled) {
            return;
        }

        log.info("Shutting down SQS Batch Publisher...");
        running.set(false);

        // Stop scheduler
        if (flushScheduler != null) {
            flushScheduler.shutdown();
            try {
                if (!flushScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    flushScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                flushScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Flush all remaining batches
        log.info("Flushing remaining batched messages...");
        int totalFlushed = 0;
        for (String roomId : batchQueues.keySet()) {
            BlockingQueue<QueueMessage> queue = batchQueues.get(roomId);
            int queueSize = queue.size();
            if (queueSize > 0) {
                flushBatchForRoom(roomId);
                totalFlushed += queueSize;
            }
        }
        
        log.info("SQS Batch Publisher shutdown complete");
        log.info("  Flushed {} pending messages", totalFlushed);
        log.info("  Total messages queued: {}", messagesQueued.get());
        log.info("  Total batches sent: {}", batchesSent.get());
        log.info("  Total messages in batches: {}", messagesInBatches.get());
        log.info("  Total batches failed: {}", batchesFailed.get());
        log.info("  Total messages dropped: {}", messagesDropped.get());
        log.info("  API call reduction: {}%", calculateApiReduction());
    }

    /**
     * Calculate API call reduction percentage
     */
    private double calculateApiReduction() {
        long totalMessages = messagesInBatches.get();
        long totalBatches = batchesSent.get();
        
        if (totalMessages == 0) {
            return 0.0;
        }
        
        // Without batching: totalMessages API calls
        // With batching: totalBatches API calls
        double reduction = ((double) (totalMessages - totalBatches) / totalMessages) * 100;
        return Math.round(reduction * 100.0) / 100.0;
    }

    /**
     * Get metrics
     */
    public long getMessagesQueued() {
        return messagesQueued.get();
    }

    public long getBatchesSent() {
        return batchesSent.get();
    }

    public long getMessagesInBatches() {
        return messagesInBatches.get();
    }

    public long getBatchesFailed() {
        return batchesFailed.get();
    }

    public long getMessagesDropped() {
        return messagesDropped.get();
    }

    public double getApiReduction() {
        return calculateApiReduction();
    }

    /**
     * Get current batch queue depths for debugging
     */
    public Map<String, Integer> getBatchQueueDepths() {
        Map<String, Integer> depths = new HashMap<>();
        batchQueues.forEach((roomId, queue) -> depths.put(roomId, queue.size()));
        return depths;
    }

    public boolean isEnabled() {
        return batchEnabled;
    }
} 
