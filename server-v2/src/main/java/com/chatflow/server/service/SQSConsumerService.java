package com.chatflow.server.service;

import com.chatflow.server.model.QueueMessage;
import com.chatflow.server.persistence.BatchMessageWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
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
    private RoomManager roomManager;

    @Autowired
    private SQSService sqsService;

    @Autowired
    private ConsumerPartitioningService partitioningService;

    @Autowired
    private BatchMessageWriter batchMessageWriter;

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
    private final AtomicLong messagesDeletedWithoutBroadcast = new AtomicLong(0);

    @PostConstruct
    public void init() {
        if (!consumerEnabled) {
            log.info("SQS Consumer is disabled");
            return;
        }

        log.info("Initializing SQS Consumer Service with {} threads", consumerThreads);

        // FIXED: Set running to true BEFORE creating threads
        running.set(true);

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
                });

        // Start consumers
        startConsumers();

        log.info("SQS Consumer Service initialized successfully");
    }

    /**
     * Start consumer threads for all rooms
     * FIXED: Correct room assignment logic - no more idle threads
     * FIXED: Uses ConsumerPartitioningService for multi-instance deployments
     */
    private void startConsumers() {
        // FIXED: Get assigned rooms from partitioning service
        List<String> assignedRooms = partitioningService.getAssignedRooms();

        if (assignedRooms.isEmpty()) {
            log.error("No rooms assigned to this instance! Consumer will not start.");
            return;
        }

        log.info("This instance will consume {} rooms: {}", assignedRooms.size(), assignedRooms);

        // FIXED: Only create as many active threads as assigned rooms (or configured
        // max)
        int roomsToConsume = assignedRooms.size();
        int activeThreads = Math.min(consumerThreads, roomsToConsume);

        // Calculate base rooms per thread and remainder
        int baseRoomsPerThread = roomsToConsume / activeThreads;
        int extraRooms = roomsToConsume % activeThreads;

        log.info("Distributing {} assigned rooms across {} consumer threads", roomsToConsume, activeThreads);
        log.info("Base rooms per thread: {}, Extra rooms to distribute: {}",
                baseRoomsPerThread, extraRooms);

        int currentRoomIndex = 0;

        for (int threadIndex = 0; threadIndex < activeThreads; threadIndex++) {
            // First 'extraRooms' threads get one extra room
            int roomsForThread = baseRoomsPerThread + (threadIndex < extraRooms ? 1 : 0);

            // Get subset of assigned rooms for this thread
            List<String> threadRooms = new ArrayList<>();
            for (int i = 0; i < roomsForThread && currentRoomIndex < assignedRooms.size(); i++) {
                threadRooms.add(assignedRooms.get(currentRoomIndex));
                currentRoomIndex++;
            }

            if (threadRooms.isEmpty()) {
                continue; // Skip if no rooms for this thread
            }

            final int threadNum = threadIndex + 1;
            final List<String> roomsForConsumer = new ArrayList<>(threadRooms);

            consumerExecutor.submit(() -> {
                log.info("Consumer thread {} started, handling rooms {} ({} rooms)",
                        threadNum, roomsForConsumer, roomsForConsumer.size());

                try {
                    consumeMessagesFromRooms(roomsForConsumer, threadNum);
                } catch (Exception e) {
                    log.error("Consumer thread {} crashed: {}", threadNum, e.getMessage(), e);
                } finally {
                    log.info("Consumer thread {} stopped", threadNum);
                }
            });
        }

        log.info("Started {} consumer threads for {} assigned rooms", activeThreads, roomsToConsume);
    }

    /**
     * Consume messages from a list of assigned rooms (supports partitioning)
     * FIXED: Better error handling and conditional sleep
     */
    private void consumeMessagesFromRooms(List<String> roomIds, int threadNum) {
        log.info("Thread {}: Starting message consumption loop for rooms {}", threadNum, roomIds);

        int loopIterations = 0;

        while (running.get()) {
            loopIterations++;
            boolean anyMessagesReceived = false;

            try {
                // Poll each assigned room
                for (String roomId : roomIds) {
                    if (!running.get()) {
                        log.info("Thread {}: Stopping due to shutdown signal", threadNum);
                        break;
                    }

                    String queueUrl = sqsService.getCachedQueueUrl(roomId);

                    if (queueUrl == null) {
                        // FIXED: Will auto-retry via lazy loading in SQSService
                        if (loopIterations % 100 == 1) { // Log occasionally, not every time
                            log.warn("Thread {}: Queue URL not found for room {} (will retry)",
                                    threadNum, roomId);
                        }
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
                            anyMessagesReceived = true;
                            log.debug("Thread {}: Received {} messages from room {}",
                                    threadNum, messages.size(), roomId);
                        }

                        // Process each message
                        for (Message message : messages) {
                            if (!running.get()) {
                                break;
                            }
                            processMessage(message, queueUrl, roomId, threadNum);
                        }

                    } catch (Exception e) {
                        log.error("Thread {}: Error receiving messages from room {}: {}",
                                threadNum, roomId, e.getMessage());
                        // Continue to next room on error
                    }
                }

                // FIXED: Only sleep if no messages received (optimization)
                if (!anyMessagesReceived && running.get()) {
                    Thread.sleep(100);
                }

            } catch (InterruptedException e) {
                log.info("Thread {} interrupted, stopping", threadNum);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Thread {}: Unexpected error in consumer loop: {}",
                        threadNum, e.getMessage(), e);

                // Sleep before retrying to avoid tight error loop
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Thread {}: Exited message consumption loop after {} iterations",
                threadNum, loopIterations);
    }

    /**
     * Process a single message
     * FIXED: Only delete message after successful broadcast
     * Assignment 3: Added database persistence
     */
    private void processMessage(Message message, String queueUrl, String roomId, int threadNum) {
        try {
            // Parse message body
            QueueMessage queueMessage = objectMapper.readValue(
                    message.body(),
                    QueueMessage.class);

            log.debug("Thread {}: Processing message {} from room {}",
                    threadNum, queueMessage.getMessageId(), roomId);

            // Broadcast to WebSocket clients (existing functionality)
            RoomManager.BroadcastResult result = roomManager.broadcastToRoom(queueMessage);

            // Assignment 3: Enqueue for database persistence
            boolean enqueued = batchMessageWriter.enqueue(queueMessage);
            if (!enqueued) {
                log.warn("Thread {}: Failed to enqueue message {} for persistence (buffer full)",
                        threadNum, queueMessage.getMessageId());
            }

            // Delete if broadcast succeeded OR if persisted to DB
            if (enqueued) {
                deleteMessage(queueUrl, message.receiptHandle());
                messagesProcessed.incrementAndGet();

                if (result.getSuccessCount() == 0) {
                    // Persisted but not broadcast - that's OK
                    log.debug("Message persisted to DB but no active clients");
                }
            }

        } catch (Exception e) {
            log.error("Thread {}: Failed to process message from room {}: {}",
                    threadNum, roomId, e.getMessage(), e);
            messagesFailed.incrementAndGet();

            // FIXED: Don't delete message on processing error - it will become visible
            // again
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
     * FIXED: Better shutdown logging
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

                    // Wait a bit more for forced shutdown
                    if (!consumerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        log.error("Consumer threads still running after forced shutdown");
                    }
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for consumer shutdown");
                consumerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("SQS Consumer Service shutdown complete.");
        log.info("  Messages processed: {}", messagesProcessed.get());
        log.info("  Messages failed: {}", messagesFailed.get());
        log.info("  Messages that couldn't broadcast: {}", messagesDeletedWithoutBroadcast.get());
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

    public long getMessagesDeletedWithoutBroadcast() {
        return messagesDeletedWithoutBroadcast.get();
    }

    public boolean isRunning() {
        return running.get();
    }
}