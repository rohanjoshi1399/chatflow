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
    private static final int INITIAL_THREADS = 32;
    private static final int OPTIMAL_THREADS = 64;
    private static final int MESSAGES_PER_INITIAL_THREAD = 1000;
    private static final int TOTAL_MESSAGES = 500000;
    private static final int WARMUP_MESSAGES = INITIAL_THREADS * MESSAGES_PER_INITIAL_THREAD;
    private static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_MESSAGES;
    private static final int MAX_RETRIES = 5;
    private static final int QUEUE_SIZE = 2000;
    private static final int MAX_IN_FLIGHT_PER_CONNECTION = 50;

    private final AtomicInteger warmupSuccessCount = new AtomicInteger(0);
    private final AtomicInteger warmupErrorCount = new AtomicInteger(0);
    private final AtomicInteger mainSuccessCount = new AtomicInteger(0);
    private final AtomicInteger mainErrorCount = new AtomicInteger(0);

    private final AtomicInteger warmupSendCount = new AtomicInteger(0);
    private final AtomicInteger mainSendCount = new AtomicInteger(0);
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
        System.out.println("ChatFlow Load Test Client - Part 1");
        System.out.println("=".repeat(60));
        System.out.println("Server URL: " + baseServerUrl);
        System.out.println("Total Messages: " + TOTAL_MESSAGES);
        System.out.println("Warmup: " + INITIAL_THREADS + " threads x " +
                MESSAGES_PER_INITIAL_THREAD + " messages");
        System.out.println("Main Phase: 64 threads (persistent connections)");
        System.out.println("Max In-Flight: " + MAX_IN_FLIGHT_PER_CONNECTION + " msgs/connection");
        System.out.println("=".repeat(60) + "\n");

        long startTime = System.currentTimeMillis();

        // Phase 1: Warmup
        System.out.println("PHASE 1: WARMUP (INITIAL PHASE)");
        System.out.println("-".repeat(60));
        long warmupStart = System.currentTimeMillis();
        runWarmupPhase(baseServerUrl);
        long warmupEnd = System.currentTimeMillis();
        double warmupTime = (warmupEnd - warmupStart) / 1000.0;

        System.out.printf("Warmup completed in %.2f seconds%n", warmupTime);
        System.out.printf("Sent: %d, Successful: %d, Failed: %d%n",
                warmupSendCount.get(), warmupSuccessCount.get(), warmupErrorCount.get());
        System.out.printf("Warmup throughput: %.2f messages/second%n%n",
                warmupSuccessCount.get() / Math.max(warmupTime, 0.001));

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Phase 2: Main Load Test
        System.out.println("PHASE 2: MAIN LOAD TEST");
        System.out.println("-".repeat(60));
        long mainStart = System.currentTimeMillis();
        runMainPhase(baseServerUrl);
        long mainEnd = System.currentTimeMillis();
        double mainTime = (mainEnd - mainStart) / 1000.0;

        System.out.printf("Main phase completed in %.2f seconds%n", mainTime);
        System.out.printf("Sent: %d, Successful: %d, Failed: %d%n",
                mainSendCount.get(), mainSuccessCount.get(), mainErrorCount.get());
        System.out.printf("Main phase throughput: %.2f messages/second%n%n",
                mainSuccessCount.get() / Math.max(mainTime, 0.001));

        long endTime = System.currentTimeMillis();
        double totalTime = (endTime - startTime) / 1000.0;

        printResults(totalTime, warmupTime, mainTime);
    }

    private void runWarmupPhase(String baseServerUrl) {
        BlockingQueue<ChatMessage> messageQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);

        Thread generator = new Thread(new MessageGenerator(messageQueue, WARMUP_MESSAGES));
        generator.start();

        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < INITIAL_THREADS; i++) {
            Thread thread = new Thread(() -> sendMessagesAndTerminate(baseServerUrl, messageQueue,
                    MESSAGES_PER_INITIAL_THREAD,
                    warmupSuccessCount, warmupErrorCount, warmupSendCount));
            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("All " + INITIAL_THREADS + " warmup threads terminated.");
    }

    private void runMainPhase(String baseServerUrl) {
        ExecutorService executor = Executors.newFixedThreadPool(OPTIMAL_THREADS);
        BlockingQueue<ChatMessage> messageQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);

        log.info("Creating {} threads for main phase...", OPTIMAL_THREADS);

        Thread generator = new Thread(new MessageGenerator(messageQueue, MAIN_PHASE_MESSAGES));
        generator.start();

        int messagesPerThread = MAIN_PHASE_MESSAGES / OPTIMAL_THREADS;
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < OPTIMAL_THREADS; i++) {
            final int threadMessages = (i == OPTIMAL_THREADS - 1) ?
                    MAIN_PHASE_MESSAGES - (messagesPerThread * (OPTIMAL_THREADS - 1)) :
                    messagesPerThread;

            Future<?> future = executor.submit(() -> sendMessagesWithPersistentConnection(baseServerUrl, messageQueue,
                    threadMessages, mainSuccessCount, mainErrorCount, mainSendCount));
            futures.add(future);
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("Main phase thread error: {}", e.getMessage());
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(20, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendMessagesAndTerminate(String baseServerUrl,
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

            if (!latch.await(120, TimeUnit.SECONDS)) {
                long remaining = latch.getCount();
                timeoutCount.addAndGet((int) remaining);
                for (int i = 0; i < remaining; i++) {
                    errorCount.incrementAndGet();
                }
            }

        } catch (Exception e) {
            System.err.println("[WARMUP] Error: " + e.getMessage());
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

            if (!latch.await(180, TimeUnit.SECONDS)) {
                long remaining = latch.getCount();
                timeoutCount.addAndGet((int) remaining);
                for (int i = 0; i < remaining; i++) {
                    errorCount.incrementAndGet();
                }
            }

        } catch (Exception e) {
            System.err.println("[MAIN] Error: " + e.getMessage());
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

    private void printResults(double totalTime, double warmupTime, double mainTime) {
        int totalSuccess = warmupSuccessCount.get() + mainSuccessCount.get();
        int totalFailed = warmupErrorCount.get() + mainErrorCount.get();
        int totalSent = warmupSendCount.get() + mainSendCount.get();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("FINAL RESULTS - PART 1");
        System.out.println("=".repeat(60));

        System.out.println("\nWARMUP PHASE:");
        System.out.printf("  Messages Sent: %d%n", warmupSendCount.get());
        System.out.printf("  Successful:    %d%n", warmupSuccessCount.get());
        System.out.printf("  Failed:        %d%n", warmupErrorCount.get());
        System.out.printf("  Time:          %.2f seconds%n", warmupTime);
        System.out.printf("  Throughput:    %.2f msg/sec%n", warmupSuccessCount.get() / Math.max(warmupTime, 0.001));

        System.out.println("\nMAIN PHASE:");
        System.out.printf("  Messages Sent: %d%n", mainSendCount.get());
        System.out.printf("  Successful:    %d%n", mainSuccessCount.get());
        System.out.printf("  Failed:        %d%n", mainErrorCount.get());
        System.out.printf("  Time:          %.2f seconds%n", mainTime);
        System.out.printf("  Throughput:    %.2f msg/sec%n", mainSuccessCount.get() / Math.max(mainTime, 0.001));

        System.out.println("\nOVERALL:");
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
    }
}