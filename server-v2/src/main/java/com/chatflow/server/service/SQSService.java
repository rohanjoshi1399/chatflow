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

    private final Map<String, String> queueUrlCache = new ConcurrentHashMap<>();
    private final AtomicLong messagesSent = new AtomicLong(0);

    @PostConstruct
    public void init() {
        log.info("Initializing SQS Service with queue prefix: {}", queuePrefix);
        // Pre-cache queue URLs for all 20 rooms
        for (int i = 1; i <= 20; i++) {
            String roomId = String.valueOf(i);
            try {
                String queueUrl = getQueueUrl(roomId);
                queueUrlCache.put(roomId, queueUrl);
                log.info("Cached queue URL for room {}: {}", roomId, queueUrl);
            } catch (Exception e) {
                log.error("Failed to cache queue URL for room {}", roomId, e);
            }
        }
    }

    /**
     * Publish message to SQS queue for specific room
     */
    public boolean publishMessage(QueueMessage message) {
        try {
            String queueUrl = queueUrlCache.get(message.getRoomId());
            if (queueUrl == null) {
                log.error("Queue URL not found for room: {}", message.getRoomId());
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
     * Get or create queue URL for a room
     */
    private String getQueueUrl(String roomId) {
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
            log.error("Queue {} does not exist", queueName);
            return "error";
        }
    }

    /**
     * Get queue URL from cache
     */
    public String getCachedQueueUrl(String roomId) {
        return queueUrlCache.get(roomId);
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