package com.chatflow.client;

import com.chatflow.client.model.ChatMessage;
import com.chatflow.client.generator.MessageGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LoadTestClient {
    private static final String SERVER_URL = "ws://localhost:8080/chat/";
    private static final int OPTIMAL_THREADS = 64;
    private static final int TOTAL_MESSAGES = 4320000;
    private static final int MAX_RETRIES = 5;
    private static final int QUEUE_SIZE = 2000;
    private static final int MAX_IN_FLIGHT_PER_CONNECTION = 50;

    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicInteger sendCount = new AtomicInteger(0);
    private final AtomicInteger timeoutCount = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger reconnections = new AtomicInteger(0);
    private final AtomicInteger connectionFailures = new AtomicInteger(0);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    public static void main(String[] args) {
        String serverUrl = args.length > 0 ? args[0] : SERVER_URL;

        if (!serverUrl.startsWith("ws://")) {
            log.error("Invalid WebSocket URL. Must start with ws:// or wss://");
            log.error("Usage: java LoadTestClient ws://hostname:port/chat/");
            System.exit(1);
        }

        LoadTestClient client = new LoadTestClient();
        client.runLoadTest(serverUrl);
    }

    public void runLoadTest(String baseServerUrl) {
        System.out.println("=".repeat(60));
        System.out.println("ChatFlow Load Test Client");
        System.out.println("=".repeat(60));
        System.out.println("Server URL: " + baseServerUrl);
        System.out.println("Total Messages: " + TOTAL_MESSAGES);
        System.out.println("Threads: " + OPTIMAL_THREADS + " (persistent connections)");
        System.out.println("Max In-Flight: " + MAX_IN_FLIGHT_PER_CONNECTION + " msgs/connection");
        System.out.println("=".repeat(60) + "\n");

        long startTime = System.currentTimeMillis();
        runMainPhase(baseServerUrl);
        long endTime = System.currentTimeMillis();
        double totalTime = (endTime - startTime) / 1000.0;

        printResults(totalTime);
    }

    private void runMainPhase(String baseServerUrl) {
        ExecutorService executor = Executors.newFixedThreadPool(OPTIMAL_THREADS);
        BlockingQueue<ChatMessage> messageQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);

        log.info("Creating {} threads...", OPTIMAL_THREADS);

        Thread generator = new Thread(new MessageGenerator(messageQueue, TOTAL_MESSAGES));
        generator.start();

        int messagesPerThread = TOTAL_MESSAGES / OPTIMAL_THREADS;
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < OPTIMAL_THREADS; i++) {
            final int threadMessages = (i == OPTIMAL_THREADS - 1)
                    ? TOTAL_MESSAGES - (messagesPerThread * (OPTIMAL_THREADS - 1))
                    : messagesPerThread;

            Future<?> future = executor.submit(() -> sendMessagesWithPersistentConnection(baseServerUrl, messageQueue,
                    threadMessages, successCount, errorCount, sendCount));
            futures.add(future);
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("Thread error: {}", e.getMessage());
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(20, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendMessagesWithPersistentConnection(String baseServerUrl,
            BlockingQueue<ChatMessage> messageQueue,
            int messageCount,
            AtomicInteger successCount,
            AtomicInteger errorCount,
            AtomicInteger sendCount) {

        ChatWebSocketClient client = null;
        String roomId = String.valueOf(random.nextInt(20) + 1);
        Semaphore inFlightSemaphore = new Semaphore(MAX_IN_FLIGHT_PER_CONNECTION);

        try {
            String wsUrl = baseServerUrl.endsWith("/") ? baseServerUrl + roomId : baseServerUrl + "/" + roomId;
            URI serverUri = new URI(wsUrl);
            CountDownLatch latch = new CountDownLatch(messageCount);
            client = new ChatWebSocketClient(serverUri, latch, successCount, errorCount, inFlightSemaphore);

            totalConnections.incrementAndGet();

            if (!client.connectBlocking(10, TimeUnit.SECONDS)) {
                connectionFailures.incrementAndGet();
                for (int i = 0; i < messageCount; i++) {
                    errorCount.incrementAndGet();
                }
                return;
            }

            Thread.sleep(100);

            for (int i = 0; i < messageCount; i++) {
                ChatMessage message = messageQueue.poll(30, TimeUnit.SECONDS);
                if (message == null) {
                    latch.countDown();
                    errorCount.incrementAndGet();
                    continue;
                }

                if (i % 500 == 0 && !client.isOpen()) {
                    reconnections.incrementAndGet();
                    if (!client.reconnectBlocking()) {
                        for (int j = i; j < messageCount; j++) {
                            latch.countDown();
                            errorCount.incrementAndGet();
                        }
                        break;
                    }
                    Thread.sleep(100);
                }

                if (!inFlightSemaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                    latch.countDown();
                    errorCount.incrementAndGet();
                    continue;
                }

                sendCount.incrementAndGet();
                boolean sent = sendMessageWithRetry(client, message, latch, errorCount, inFlightSemaphore);

                if (!sent) {
                    inFlightSemaphore.release();
                }
            }

            if (!latch.await(300, TimeUnit.SECONDS)) {
                long remaining = latch.getCount();
                timeoutCount.addAndGet((int) remaining);
                for (int i = 0; i < remaining; i++) {
                    errorCount.incrementAndGet();
                }
            }

        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
        } finally {
            if (client != null && client.isOpen()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                client.close();
            }
        }
    }

    private boolean sendMessageWithRetry(ChatWebSocketClient client,
            ChatMessage message,
            CountDownLatch latch,
            AtomicInteger errorCount,
            Semaphore inFlightSemaphore) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);

            for (int retry = 0; retry < MAX_RETRIES; retry++) {
                try {
                    if (!client.isOpen()) {
                        if (retry < MAX_RETRIES - 1) {
                            Thread.sleep((long) Math.pow(2, retry) * 100);
                        }
                        continue;
                    }

                    client.sendMessageWithTracking(jsonMessage);
                    return true;

                } catch (Exception e) {
                    if (retry == MAX_RETRIES - 1) {
                        errorCount.incrementAndGet();
                        latch.countDown();
                        return false;
                    } else {
                        Thread.sleep((long) Math.pow(2, retry) * 100);
                    }
                }
            }

            errorCount.incrementAndGet();
            latch.countDown();
            return false;

        } catch (Exception e) {
            errorCount.incrementAndGet();
            latch.countDown();
            return false;
        }
    }

    private void printResults(double totalTime) {
        int totalSuccess = successCount.get();
        int totalFailed = errorCount.get();
        int totalSent = sendCount.get();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("FINAL RESULTS");
        System.out.println("=".repeat(60));

        System.out.printf("  Total Sent:         %d%n", totalSent);
        System.out.printf("  Total Successful:   %d%n", totalSuccess);
        System.out.printf("  Total Failed:       %d%n", totalFailed);
        System.out.printf("  Response Timeouts:  %d%n", timeoutCount.get());
        System.out.printf("  Total Runtime:      %.2f seconds%n", totalTime);
        System.out.printf("  Overall Throughput: %.2f messages/second%n", totalSuccess / Math.max(totalTime, 0.001));

        System.out.println("\nCONNECTION STATISTICS:");
        System.out.printf("  Total Connections:    %d%n", totalConnections.get());
        System.out.printf("  Connection Failures:  %d%n", connectionFailures.get());
        System.out.printf("  Reconnections:        %d%n", reconnections.get());

        System.out.println("\nSUCCESS METRICS:");
        double successRate = totalSent > 0 ? (totalSuccess * 100.0) / totalSent : 0;
        System.out.printf("  Success Rate: %.2f%% (%d/%d)%n",
                successRate, totalSuccess, totalSent);

        System.out.println("=".repeat(60));

        // Print Metrics API URL for easy testing
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant start = now.minusSeconds((long) totalTime + 5); // Add buffer
        java.time.Instant end = now.plusSeconds(5);

        System.out.println("\n[INFO] To verify results, call the Metrics API:");
        System.out.printf(
                "http://localhost:8080/api/metrics/all?testStartTime=%s&testEndTime=%s&sampleUserId=1&sampleRoomId=1%n",
                start.toString(), end.toString());
        System.out.println("=".repeat(60));
    }
}