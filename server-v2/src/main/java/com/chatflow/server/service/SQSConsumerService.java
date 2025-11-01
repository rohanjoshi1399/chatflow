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
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class SQSConsumerService {

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomSessionManager roomManager;

    @Autowired
    private SQSService sqsService;

    @Value("${sqs.consumer.threads:40}")
    private int consumerThreads;

    @Value("${sqs.consumer.max.messages:10}")
    private int maxMessages;

    @Value("${sqs.consumer.wait.time:20}")
    private int waitTimeSeconds;

    @Value("${sqs.consumer.visibility.timeout:30}")
    private int visibilityTimeout;

    @Value("${sqs.consumer.enabled:true}")
    private boolean consumerEnabled;

    private ExecutorService consumerExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);

    @PostConstruct
    public void init() {
        if (!consumerEnabled) {
            log.info("SQS Consumer is disabled");
            return;
        }

        log.info("Initializing SQS Consumer Service with {} threads", consumerThreads);

        // Create thread pool for consumers
        consumerExecutor = Executors.newFixedThreadPool(
                consumerThreads,
                new ThreadFactory() {
                    private final AtomicLong threadCounter = new AtomicLong(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setName("sqs-consumer-" + threadCounter.incrementAndGet());
                        thread.setDaemon(false);
                        return thread;
                    }
                }
        );

        running.set(true);

        // Start consumers
        startConsumers();

        log.info("SQS Consumer Service initialized successfully");
    }

    /**
     * Start consumer threads for all rooms
     */
    private void startConsumers() {

        // Calculate rooms per thread
        int roomsPerThread = (int) Math.ceil(20.0 / consumerThreads);

        for (int threadIndex = 0; threadIndex < consumerThreads; threadIndex++) {
            int startRoom = (threadIndex * roomsPerThread) + 1;
            int endRoom = Math.min(startRoom + roomsPerThread - 1, 20);

            final int threadNum = threadIndex + 1;

            consumerExecutor.submit(() -> {
                log.info("Consumer thread {} started, handling rooms {} to {}",
                        threadNum, startRoom, endRoom);
                consumeMessages(startRoom, endRoom, threadNum);
            });
        }

        log.info("Started {} consumer threads for 20 rooms", consumerThreads);
    }

    /**
     * Consume messages from assigned rooms
     */
    private void consumeMessages(int startRoom, int endRoom, int threadNum) {
        while (running.get()) {
            try {
                // Poll each assigned room
                for (int roomId = startRoom; roomId <= endRoom; roomId++) {
                    if (!running.get()) {
                        break;
                    }

                    String roomIdStr = String.valueOf(roomId);
                    String queueUrl = sqsService.getCachedQueueUrl(roomIdStr);

                    if (queueUrl == null) {
                        log.warn("Thread {}: Queue URL not found for room {}", threadNum, roomIdStr);
                        continue;
                    }

                    try {
                        // Receive messages from queue
                        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .maxNumberOfMessages(maxMessages)
                                .waitTimeSeconds(waitTimeSeconds)
                                .visibilityTimeout(visibilityTimeout)
                                .build();

                        ReceiveMessageResponse receiveResponse = sqsClient.receiveMessage(receiveRequest);
                        List<Message> messages = receiveResponse.messages();

                        if (!messages.isEmpty()) {
                            log.debug("Thread {}: Received {} messages from room {}",
                                    threadNum, messages.size(), roomIdStr);
                        }

                        // Process each message
                        for (Message message : messages) {
                            processMessage(message, queueUrl, roomIdStr, threadNum);
                        }

                    } catch (Exception e) {
                        log.error("Thread {}: Error receiving messages from room {}: {}",
                                threadNum, roomIdStr, e.getMessage());
                    }
                }

                // Small delay to prevent tight loop when no messages
                if (running.get()) {
                    Thread.sleep(100);
                }

            } catch (InterruptedException e) {
                log.info("Thread {} interrupted, stopping", threadNum);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Thread {}: Unexpected error in consumer loop: {}", threadNum, e.getMessage(), e);
            }
        }

        log.info("Consumer thread {} stopped", threadNum);
    }

    /**
     * Process a single message
     */
    private void processMessage(Message message, String queueUrl, String roomId, int threadNum) {
        try {
            // Parse message body
            QueueMessage queueMessage = objectMapper.readValue(
                    message.body(),
                    QueueMessage.class
            );

            log.debug("Thread {}: Processing message {} from room {}",
                    threadNum, queueMessage.getMessageId(), roomId);

            // Broadcast to all sessions in the room
            roomManager.broadcastToRoom(queueMessage);

            // Delete message from queue (acknowledge)
            deleteMessage(queueUrl, message.receiptHandle());

            messagesProcessed.incrementAndGet();

        } catch (Exception e) {
            log.error("Thread {}: Failed to process message from room {}: {}",
                    threadNum, roomId, e.getMessage(), e);
            messagesFailed.incrementAndGet();

            // Don't delete message - it will become visible again for retry
        }
    }

    /**
     * Delete message from queue
     */
    private void deleteMessage(String queueUrl, String receiptHandle) {
        try {
            DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build();

            sqsClient.deleteMessage(deleteRequest);

        } catch (Exception e) {
            log.error("Failed to delete message from queue: {}", e.getMessage());
        }
    }

    /**
     * Stop all consumers gracefully
     */
    @PreDestroy
    public void shutdown() {
        if (!consumerEnabled) {
            return;
        }

        log.info("Shutting down SQS Consumer Service...");
        running.set(false);

        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
            try {
                if (!consumerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Consumer threads did not terminate in time, forcing shutdown");
                    consumerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for consumer shutdown");
                consumerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("SQS Consumer Service shutdown complete. Processed: {}, Failed: {}",
                messagesProcessed.get(), messagesFailed.get());
    }

    /**
     * Get metrics
     */
    public long getMessagesProcessed() {
        return messagesProcessed.get();
    }

    public long getMessagesFailed() {
        return messagesFailed.get();
    }

    public boolean isRunning() {
        return running.get();
    }
}