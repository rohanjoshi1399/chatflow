package com.chatflow.server.persistence;

import com.chatflow.server.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles failed database writes by sending them to a Dead Letter Queue.
 * 
 * When the batch writer can't persist messages to the database, this service
 * captures them so they're not lost. Messages are sent to an SQS queue where
 * they can be replayed manually or via an automated retry consumer.
 */
@Service
@Slf4j
public class DeadLetterQueueService {

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${dlq.enabled:true}")
    private boolean dlqEnabled;

    @Value("${dlq.queue.name:chatflow-db-dlq}")
    private String dlqQueueName;

    private String dlqQueueUrl;

    /** Tracks total messages sent to the DLQ */
    private final AtomicLong messagesSentToDLQ = new AtomicLong(0);

    /** Counts failures when sending to DLQ (truly lost messages) */
    private final AtomicLong dlqSendFailures = new AtomicLong(0);

    /**
     * Initializes the Dead Letter Queue on startup.
     * 
     * Looks up the DLQ URL from AWS. If the queue doesn't exist or can't be
     * accessed,
     * DLQ functionality will be disabled and failed messages will be lost.
     */
    @PostConstruct
    public void init() {
        if (!dlqEnabled) {
            log.warn("Dead Letter Queue is DISABLED - failed messages will be lost!");
            return;
        }

        try {
            dlqQueueUrl = sqsClient.getQueueUrl(builder -> builder.queueName(dlqQueueName))
                    .queueUrl();
            log.info("Dead Letter Queue initialized: {}", dlqQueueUrl);
        } catch (Exception e) {
            log.error("Failed to initialize DLQ: {}. DLQ will be disabled.", e.getMessage());
            dlqEnabled = false;
        }
    }

    /**
     * Sends failed messages to the Dead Letter Queue for later recovery.
     * 
     * Each message is wrapped with failure metadata (reason, timestamp, attempt
     * count)
     * before being sent to SQS. If sending to the DLQ itself fails, the message is
     * truly lost and will be logged.
     * 
     * @param messages    batch of messages that couldn't be written to the database
     * @param errorReason description of why the database write failed
     */
    public void sendToDLQ(List<QueueMessage> messages, String errorReason) {
        if (!dlqEnabled) {
            log.warn("DLQ disabled - dropping {} failed messages", messages.size());
            return;
        }

        if (messages == null || messages.isEmpty()) {
            return;
        }

        log.warn("Sending {} failed messages to DLQ. Reason: {}", messages.size(), errorReason);

        for (QueueMessage message : messages) {
            try {
                DLQMessage dlqMessage = DLQMessage.builder()
                        .originalMessage(message)
                        .failureReason(errorReason)
                        .failureTimestamp(System.currentTimeMillis())
                        .attemptCount(1)
                        .build();

                String messageBody = objectMapper.writeValueAsString(dlqMessage);

                SendMessageRequest request = SendMessageRequest.builder()
                        .queueUrl(dlqQueueUrl)
                        .messageBody(messageBody)
                        .messageGroupId("database-failures")
                        .messageDeduplicationId(message.getMessageId() + "-" + System.currentTimeMillis())
                        .build();

                sqsClient.sendMessage(request);
                messagesSentToDLQ.incrementAndGet();

            } catch (Exception e) {
                dlqSendFailures.incrementAndGet();
                log.error("Failed to send message {} to DLQ: {}",
                        message.getMessageId(), e.getMessage());
                log.error("LOST MESSAGE: {}", message);
            }
        }

        log.info("Sent {}/{} messages to DLQ",
                messages.size() - dlqSendFailures.get(), messages.size());
    }

    /**
     * Checks if the DLQ is configured and ready to accept messages.
     * 
     * @return true if DLQ is enabled and queue URL is available
     */
    public boolean isOperational() {
        return dlqEnabled && dlqQueueUrl != null;
    }

    /**
     * Returns count of messages successfully sent to DLQ.
     * 
     * @return total DLQ message count
     */
    public long getMessagesSentToDLQ() {
        return messagesSentToDLQ.get();
    }

    /**
     * Returns count of failures when sending to DLQ.
     * These represent truly lost messages that couldn't be recovered.
     * 
     * @return total DLQ send failure count
     */
    public long getDlqSendFailures() {
        return dlqSendFailures.get();
    }

    /**
     * Returns the SQS queue URL for the DLQ.
     * 
     * @return DLQ queue URL or null if not initialized
     */
    public String getDlqQueueUrl() {
        return dlqQueueUrl;
    }

    /**
     * Wrapper class for DLQ messages with failure metadata.
     * Includes the original message plus information about why and when it failed.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DLQMessage {
        private QueueMessage originalMessage;
        private String failureReason;
        private long failureTimestamp;
        private int attemptCount;
    }
}
