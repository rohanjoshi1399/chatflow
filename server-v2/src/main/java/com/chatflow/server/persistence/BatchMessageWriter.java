package com.chatflow.server.persistence;

import com.chatflow.server.model.QueueMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages asynchronous batch writing of messages to the database.
 * 
 * This service acts as a buffer between the high-throughput SQS consumers and
 * the database.
 * It collects messages in memory and flushes them in batches to maximize write
 * efficiency.
 * The single-threaded design keeps things simple while still achieving
 * excellent performance.
 * 
 * Messages are flushed when either the batch size limit is reached or the flush
 * interval
 * elapses, whichever comes first. This balances throughput with latency.
 * 
 * @see MessagePersistenceService for the actual database write operations
 */
@Service
@Slf4j
public class BatchMessageWriter {

    @Autowired(required = false)
    private MessagePersistenceService persistenceService;

    @Autowired(required = false)
    private DeadLetterQueueService dlqService;

    /** Maximum number of messages to include in a single batch write */
    @Value("${batch.writer.size:1000}")
    private int batchSize;

    /** Maximum time to wait before flushing a partial batch (in milliseconds) */
    @Value("${batch.writer.flush.interval.ms:1000}")
    private long flushIntervalMs;

    /** Maximum capacity of the in-memory message buffer */
    @Value("${batch.writer.buffer.capacity:10000}")
    private int bufferCapacity;

    /** Thread-safe queue for buffering messages before batch writes */
    private BlockingQueue<QueueMessage> messageBuffer;

    /** Background worker thread that processes batches */
    private Thread writerThread;

    /** Flag to signal when the writer should stop processing */
    private volatile boolean running = false;

    /** Counts total messages added to the buffer */
    private final AtomicLong messagesEnqueued = new AtomicLong(0);

    /** Counts total messages successfully written to database */
    private final AtomicLong messagesWritten = new AtomicLong(0);

    /** Counts total number of batch write operations */
    private final AtomicLong batchesWritten = new AtomicLong(0);

    /** Counts messages dropped due to buffer being full */
    private final AtomicLong droppedMessages = new AtomicLong(0);

    /** Counts batch write failures */
    private final AtomicLong writeErrors = new AtomicLong(0);

    /**
     * Initializes the batch writer on Spring application startup.
     * 
     * Validates configuration, creates the message buffer, and starts the
     * background
     * processing thread. If the database isn't configured, the writer will be
     * disabled
     * and messages won't be persisted.
     */
    @PostConstruct
    public void init() {
        if (batchSize > bufferCapacity) {
            throw new IllegalStateException(
                    "batch.writer.size (" + batchSize +
                            ") cannot exceed batch.writer.buffer.capacity (" + bufferCapacity + ")");
        }

        if (persistenceService == null) {
            log.warn("MessagePersistenceService not available - database persistence is DISABLED");
            log.warn("Messages will be broadcast via WebSocket but NOT persisted to database");
            return;
        }

        messageBuffer = new LinkedBlockingQueue<>(bufferCapacity);
        running = true;

        writerThread = new Thread(this::processBatches, "db-batch-writer");
        writerThread.setDaemon(false);
        writerThread.start();

        log.info("BatchMessageWriter started: batchSize={}, flushInterval={}ms, bufferCapacity={}",
                batchSize, flushIntervalMs, bufferCapacity);
    }

    /**
     * Adds a message to the buffer for eventual batch writing.
     * 
     * This is a non-blocking operation - if the buffer is full, the message will be
     * dropped rather than blocking the caller. Dropped messages are logged and
     * counted
     * for monitoring purposes.
     * 
     * @param message the message to persist
     * @return true if successfully enqueued, false if buffer full or database not
     *         configured
     */
    public boolean enqueue(QueueMessage message) {
        if (persistenceService == null) {
            return false;
        }

        boolean added = messageBuffer.offer(message);

        if (added) {
            messagesEnqueued.incrementAndGet();
        } else {
            droppedMessages.incrementAndGet();
            log.warn("Message buffer full ({} messages), dropping message: {}",
                    bufferCapacity, message.getMessageId());
        }

        return added;
    }

    /**
     * Main processing loop that runs in the background thread.
     * 
     * Continuously polls for messages and flushes batches when either the size
     * limit
     * or time interval is reached. Handles graceful shutdown by flushing any
     * remaining
     * messages before exiting.
     */
    private void processBatches() {
        List<QueueMessage> batch = new ArrayList<>(batchSize);
        long lastFlush = System.currentTimeMillis();

        log.info("Batch processor started");

        while (running) {
            try {
                // Wait for message with timeout (allows periodic flush check)
                QueueMessage msg = messageBuffer.poll(100, TimeUnit.MILLISECONDS);

                if (msg != null) {
                    batch.add(msg);
                }

                long now = System.currentTimeMillis();
                long timeSinceFlush = now - lastFlush;

                boolean shouldFlush = batch.size() >= batchSize ||
                        (batch.size() > 0 && timeSinceFlush >= flushIntervalMs);

                if (shouldFlush) {
                    flushBatch(batch);
                    batch.clear();
                    lastFlush = now;
                }

            } catch (InterruptedException e) {
                log.warn("Batch writer interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in batch writer loop: {}", e.getMessage(), e);
            }
        }

        if (!batch.isEmpty()) {
            log.info("Flushing final batch of {} messages on shutdown", batch.size());
            flushBatch(batch);
        }

        log.info("Batch processor stopped");
    }

    /**
     * Writes a batch of messages to the database.
     * 
     * Performs both the main message insert and the user activity update as
     * separate
     * operations. If the write fails, the batch is sent to the Dead Letter Queue if
     * available, otherwise the messages are lost (but logged for debugging).
     * 
     * @param batch list of messages to write
     */
    private void flushBatch(List<QueueMessage> batch) {
        if (batch.isEmpty()) {
            return;
        }

        try {
            long start = System.currentTimeMillis();

            int[] results = persistenceService.batchInsertMessages(batch);

            persistenceService.batchUpsertUserActivity(batch);

            long duration = System.currentTimeMillis() - start;

            messagesWritten.addAndGet(batch.size());
            batchesWritten.incrementAndGet();

            double avgPerMessage = batch.size() > 0 ? (double) duration / batch.size() : 0;
            log.debug("Flushed batch of {} messages in {}ms (avg: {:.2f}ms per message)",
                    batch.size(), duration, avgPerMessage);

        } catch (Exception e) {
            writeErrors.incrementAndGet();
            log.error("Failed to flush batch of {} messages: {}", batch.size(), e.getMessage(), e);

            if (dlqService != null && dlqService.isOperational()) {
                dlqService.sendToDLQ(batch, "Database write failure: " + e.getMessage());
                log.info("Failed batch sent to DLQ for manual recovery");
            } else {
                log.error("DLQ not available - {} messages are LOST!", batch.size());
                for (int i = 0; i < Math.min(3, batch.size()); i++) {
                    log.error("LOST MESSAGE {}: {}", i + 1, batch.get(i));
                }
            }
        }
    }

    /**
     * Performs graceful shutdown of the batch writer.
     * 
     * Stops the processing loop and waits for the background thread to finish,
     * ensuring all buffered messages are flushed. Logs final metrics before
     * exiting.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down BatchMessageWriter...");
        running = false;

        if (writerThread != null) {
            try {
                writerThread.join(5000);

                if (writerThread.isAlive()) {
                    log.warn("Writer thread still alive after 5 seconds");
                    writerThread.interrupt();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for writer thread shutdown");
                Thread.currentThread().interrupt();
            }
        }

        log.info("BatchMessageWriter shutdown complete");
        log.info("  Messages enqueued: {}", messagesEnqueued.get());
        log.info("  Messages written: {}", messagesWritten.get());
        log.info("  Batches written: {}", batchesWritten.get());
        log.info("  Dropped messages: {}", droppedMessages.get());
        log.info("  Write errors: {}", writeErrors.get());
    }

    /**
     * Returns count of messages added to the buffer.
     * 
     * @return total enqueued message count
     */
    public long getMessagesEnqueued() {
        return messagesEnqueued.get();
    }

    /**
     * Returns count of messages successfully written to database.
     * 
     * @return total written message count
     */
    public long getMessagesWritten() {
        return messagesWritten.get();
    }

    /**
     * Returns count of batch write operations performed.
     * 
     * @return total batch count
     */
    public long getBatchesWritten() {
        return batchesWritten.get();
    }

    /**
     * Returns count of messages dropped due to buffer being full.
     * 
     * @return total dropped message count
     */
    public long getDroppedMessages() {
        return droppedMessages.get();
    }

    /**
     * Returns count of batch write errors encountered.
     * 
     * @return total error count
     */
    public long getWriteErrors() {
        return writeErrors.get();
    }

    /**
     * Returns current number of messages waiting in the buffer.
     * 
     * @return current buffer size
     */
    public int getBufferSize() {
        return messageBuffer.size();
    }

    /**
     * Returns configured batch size.
     * 
     * @return messages per batch
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Returns configured flush interval in milliseconds.
     * 
     * @return flush interval
     */
    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }
}
