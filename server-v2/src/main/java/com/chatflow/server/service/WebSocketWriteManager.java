package com.chatflow.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages WebSocket session writes to prevent concurrent write exceptions.
 * Refactored to use a shared ExecutorService instead of a thread per session.
 */
@Service
@Slf4j
public class WebSocketWriteManager {

    // Write queue per session
    private final ConcurrentHashMap<String, BlockingQueue<TextMessage>> sessionWriteQueues = new ConcurrentHashMap<>();

    // WIP counter per session for serialized execution
    private final ConcurrentHashMap<String, AtomicInteger> sessionWip = new ConcurrentHashMap<>();

    // Track which sessions are active
    private final ConcurrentHashMap<String, AtomicBoolean> sessionActive = new ConcurrentHashMap<>();

    // Shared thread pool for all writes
    private ExecutorService writerExecutor;

    @Value("${websocket.writer.threads:50}")
    private int writerThreads;

    // Metrics
    private final AtomicLong totalMessagesSent = new AtomicLong(0);
    private final AtomicLong totalMessagesQueued = new AtomicLong(0);
    private final AtomicLong totalMessagesDropped = new AtomicLong(0);
    private final AtomicLong totalWriteErrors = new AtomicLong(0);

    private static final int WRITE_QUEUE_CAPACITY = 1000;

    @PostConstruct
    public void init() {
        log.info("Initializing WebSocketWriteManager with {} writer threads", writerThreads);
        writerExecutor = Executors.newFixedThreadPool(writerThreads, r -> {
            Thread t = new Thread(r, "ws-writer-pool");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Register a WebSocket session for managed writes
     */
    public void registerSession(WebSocketSession session) {
        String sessionId = session.getId();

        if (sessionWriteQueues.containsKey(sessionId)) {
            log.warn("Session {} already registered, skipping", sessionId);
            return;
        }

        // Create write queue for this session
        BlockingQueue<TextMessage> writeQueue = new LinkedBlockingQueue<>(WRITE_QUEUE_CAPACITY);
        sessionWriteQueues.put(sessionId, writeQueue);
        sessionWip.put(sessionId, new AtomicInteger(0));

        // Create active flag
        AtomicBoolean active = new AtomicBoolean(true);
        sessionActive.put(sessionId, active);

        log.debug("Registered session {}", sessionId);
    }

    /**
     * Unregister a WebSocket session
     */
    public void unregisterSession(String sessionId) {
        log.debug("Unregistering session {}", sessionId);

        // Mark as inactive
        AtomicBoolean active = sessionActive.remove(sessionId);
        if (active != null) {
            active.set(false);
        }

        // Remove tracking structures
        sessionWip.remove(sessionId);
        BlockingQueue<TextMessage> queue = sessionWriteQueues.remove(sessionId);

        // Log dropped messages if any
        if (queue != null && !queue.isEmpty()) {
            log.warn("Session {} unregistered with {} messages still queued",
                    sessionId, queue.size());
            totalMessagesDropped.addAndGet(queue.size());
        }
    }

    /**
     * Send a message to a WebSocket session (thread-safe)
     * Message is queued and sent by the shared executor
     * 
     * @return true if queued successfully, false if queue full or session not
     *         registered
     */
    public boolean sendMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        BlockingQueue<TextMessage> writeQueue = sessionWriteQueues.get(sessionId);

        if (writeQueue == null) {
            log.warn("Attempted to send to unregistered session {}", sessionId);
            return false;
        }

        AtomicBoolean active = sessionActive.get(sessionId);
        if (active == null || !active.get()) {
            log.debug("Session {} is inactive, dropping message", sessionId);
            totalMessagesDropped.incrementAndGet();
            return false;
        }

        boolean queued = writeQueue.offer(message);

        if (queued) {
            totalMessagesQueued.incrementAndGet();
            scheduleWrite(sessionId, session, writeQueue);
        } else {
            log.warn("Write queue full for session {}, dropping message", sessionId);
            totalMessagesDropped.incrementAndGet();
        }

        return queued;
    }

    /**
     * Send a string message to a WebSocket session (convenience method)
     */
    public boolean sendMessage(WebSocketSession session, String message) {
        return sendMessage(session, new TextMessage(message));
    }

    /**
     * Schedule a write task for the session if not already running
     * Uses WIP (Work-In-Progress) pattern to ensure serialized writes
     */
    private void scheduleWrite(String sessionId, WebSocketSession session, BlockingQueue<TextMessage> queue) {
        AtomicInteger wip = sessionWip.get(sessionId);
        if (wip == null)
            return;

        if (wip.getAndIncrement() == 0) {
            try {
                writerExecutor.execute(() -> processWriteQueue(sessionId, session, queue, wip));
            } catch (RejectedExecutionException e) {
                log.error("Failed to submit write task for session {}: {}", sessionId, e.getMessage());
                wip.decrementAndGet(); // Revert increment since we failed to run
            }
        }
    }

    /**
     * Process the write queue for a session
     * Runs in the thread pool
     */
    private void processWriteQueue(String sessionId, WebSocketSession session, BlockingQueue<TextMessage> queue,
            AtomicInteger wip) {
        int missed = 1;

        do {
            // Process all available messages
            TextMessage message;
            while ((message = queue.poll()) != null) {
                if (!session.isOpen()) {
                    log.warn("Session {} closed during write processing", sessionId);
                    unregisterSession(sessionId);
                    return;
                }

                try {
                    session.sendMessage(message);
                    totalMessagesSent.incrementAndGet();
                } catch (IOException e) {
                    log.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
                    totalWriteErrors.incrementAndGet();
                    // If write fails, assume session is dead
                    unregisterSession(sessionId);
                    return;
                } catch (Exception e) {
                    log.error("Unexpected error sending to session {}: {}", sessionId, e.getMessage());
                    totalWriteErrors.incrementAndGet();
                }
            }

            // Check if more work arrived while we were processing
            missed = wip.addAndGet(-missed);
        } while (missed != 0);
    }

    /**
     * Gracefully shutdown
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down WebSocketWriteManager...");

        if (writerExecutor != null) {
            writerExecutor.shutdown();
            try {
                if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    writerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                writerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        sessionWriteQueues.clear();
        sessionWip.clear();
        sessionActive.clear();

        log.info("WebSocketWriteManager shutdown complete");
    }

    /**
     * Get metrics for monitoring
     */
    public long getTotalMessagesSent() {
        return totalMessagesSent.get();
    }

    public long getTotalMessagesQueued() {
        return totalMessagesQueued.get();
    }

    public long getTotalMessagesDropped() {
        return totalMessagesDropped.get();
    }

    public long getTotalWriteErrors() {
        return totalWriteErrors.get();
    }

    public int getActiveSessionCount() {
        return sessionWriteQueues.size();
    }

    public int getActiveWriterThreadCount() {
        if (writerExecutor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) writerExecutor).getActiveCount();
        }
        return 0;
    }
}