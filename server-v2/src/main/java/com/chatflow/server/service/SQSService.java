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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class SQSService {

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${sqs.queue.prefix:chatflow-room-}")
    private String queuePrefix;

    @Value("${sqs.fifo.enabled:true}")
    private boolean fifoEnabled;

    @Value("${sqs.queue.retry.interval.ms:60000}")
    private long retryIntervalMs;

    private final Map<String, String> queueUrlCache = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> queueUrlRetryTime = new ConcurrentHashMap<>();
    private final AtomicLong messagesSent = new AtomicLong(0);

    @PostConstruct
    public void init() {
        log.info("Initializing SQS Service with queue prefix: {}", queuePrefix);
        log.info("Queue URL lazy loading enabled with retry interval: {}ms", retryIntervalMs);
        log.info("SQS Service initialized. Queue URLs will be loaded on-demand.");
    }

    /**
     * Publish message to SQS queue for specific room
     */
    public boolean publishMessage(QueueMessage message) {
        try {
            String queueUrl = getCachedQueueUrl(message.getRoomId());
            if (queueUrl == null) {
                log.error("Queue URL not available for room: {}", message.getRoomId());
                return false;
            }

            String messageBody = objectMapper.writeValueAsString(message);
            
            SendMessageRequest.Builder requestBuilder = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody);

            // For FIFO queues, add required attributes
            if (fifoEnabled) {
                requestBuilder
                        .messageGroupId(message.getRoomId())
                        .messageDeduplicationId(message.getMessageId());
            }

            SendMessageRequest request = requestBuilder.build();
            SendMessageResponse response = sqsClient.sendMessage(request);

            messagesSent.incrementAndGet();
            
            log.debug("Published message to room {}: messageId={}, sqsMessageId={}", 
                    message.getRoomId(), message.getMessageId(), response.messageId());
            
            return true;

        } catch (Exception e) {
            log.error("Failed to publish message to SQS for room {}: {}", 
                    message.getRoomId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get cached queue URL with lazy loading and retry logic
     * Implements retry mechanism for failed queue URL loads
     */
    public String getCachedQueueUrl(String roomId) {
        // Check cache first
        String cachedUrl = queueUrlCache.get(roomId);
        
        if (cachedUrl != null) {
            return cachedUrl;
        }
        
        // Not in cache - check if we should (re)try loading
        AtomicLong lastRetry = queueUrlRetryTime.get(roomId);
        long currentTime = System.currentTimeMillis();
        
        // Try to load if:
        // 1. Never tried before (lastRetry == null)
        // 2. Enough time has passed since last failure
        if (lastRetry == null || (currentTime - lastRetry.get()) > retryIntervalMs) {
            try {
                log.info("Loading queue URL for room {} (first time or retry)", roomId);
                String queueUrl = loadQueueUrl(roomId);
                
                if (queueUrl != null) {
                    queueUrlCache.put(roomId, queueUrl);
                    queueUrlRetryTime.remove(roomId);  // Success - stop retrying
                    log.info("Successfully loaded queue URL for room {}: {}", roomId, queueUrl);
                    return queueUrl;
                }
            } catch (Exception e) {
                log.warn("Failed to load queue URL for room {}: {}. Will retry in {}ms", 
                        roomId, e.getMessage(), retryIntervalMs);
                
                // Update retry time
                queueUrlRetryTime.computeIfAbsent(roomId, k -> new AtomicLong()).set(currentTime);
            }
        } else {
            // Too soon to retry
            long timeUntilRetry = retryIntervalMs - (currentTime - lastRetry.get());
            log.debug("Queue URL for room {} not available. Next retry in {}ms", 
                    roomId, timeUntilRetry);
        }
        
        return null;
    }

    /**
     * Load queue URL from SQS (called by getCachedQueueUrl)
     * FIXED: Separated from caching logic for better testability
     */
    private String loadQueueUrl(String roomId) {
        String queueName = queuePrefix + roomId;
        if (fifoEnabled) {
            queueName += ".fifo";
        }

        try {
            // Try to get existing queue
            GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                    .queueName(queueName)
                    .build();
            
            GetQueueUrlResponse response = sqsClient.getQueueUrl(getQueueRequest);
            return response.queueUrl();

        } catch (QueueDoesNotExistException e) {
            log.warn("Queue {} does not exist. Skipping auto-creation (manual creation required).", 
                    queueName);
            return null;
        } catch (Exception e) {
            log.error("Error loading queue URL for {}: {}", queueName, e.getMessage());
            throw e;
        }
    }

    /**
     * Get total messages sent
     */
    public long getMessagesSent() {
        return messagesSent.get();
    }

    /**
     * Get queue attributes for monitoring
     */
    public Map<String, String> getQueueAttributes(String roomId) {
        try {
            String queueUrl = queueUrlCache.get(roomId);
            if (queueUrl == null) {
                log.debug("Queue URL not cached for room {}, attempting to load", roomId);
                queueUrl = getCachedQueueUrl(roomId);
            }
            
            if (queueUrl == null) {
                return new HashMap<>();
            }

            GetQueueAttributesRequest request = GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED
                    )
                    .build();

            GetQueueAttributesResponse response = sqsClient.getQueueAttributes(request);
            
            Map<String, String> attrs = new HashMap<>();
            response.attributes().forEach((key, value) -> attrs.put(key.toString(), value));
            
            return attrs;

        } catch (Exception e) {
            log.error("Failed to get queue attributes for room {}: {}", roomId, e.getMessage());
            return new HashMap<>();
        }
    }
}