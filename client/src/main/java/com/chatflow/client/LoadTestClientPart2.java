package com.chatflow.client;

import com.chatflow.client.metrics.MetricsCollector;
import com.chatflow.client.model.ChatMessage;
import com.chatflow.client.generator.MessageGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LoadTestClientPart2 {
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

    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger reconnections = new AtomicInteger(0);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    // Metrics collection
    private final MetricsCollector metricsCollector = new MetricsCollector();

    public static void main(String[] args) {
        String serverUrl = args.length > 0 ? args[0] : SERVER_URL;
        LoadTestClientPart2 client = new LoadTestClientPart2();
        client.runLoadTest(serverUrl);
    }

    public void runLoadTest(String baseServerUrl) {
        System.out.println("=".repeat(60));
        System.out.println("ChatFlow Load Test Client - Part 2 (With Metrics)");
        System.out.println("=".repeat(60));
        System.out.println("Server URL: " + baseServerUrl);
        System.out.println("Total Messages: " + TOTAL_MESSAGES);
        System.out.println("Collecting detailed performance metrics...");
        System.out.println("=".repeat(60) + "\n");

        long startTime = System.currentTimeMillis();

        // Phase 1: Warmup (no metrics)
        System.out.println("PHASE 1: WARMUP");
        System.out.println("-".repeat(60));
        long warmupStart = System.currentTimeMillis();
        runWarmupPhase(baseServerUrl);
        long warmupEnd = System.currentTimeMillis();
        double warmupTime = (warmupEnd - warmupStart) / 1000.0;
        System.out.printf("Warmup: %.2fs, %d successful%n%n",
                warmupTime, warmupSuccessCount.get());

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Phase 2: Main with metrics
        System.out.println("PHASE 2: MAIN (WITH METRICS COLLECTION)");
        System.out.println("-".repeat(60));
        long mainStart = System.currentTimeMillis();
        runMainPhase(baseServerUrl);
        long mainEnd = System.currentTimeMillis();
        double mainTime = (mainEnd - mainStart) / 1000.0;
        System.out.printf("Main phase: %.2fs, %d successful%n%n",
                mainTime, mainSuccessCount.get());

        long endTime = System.currentTimeMillis();
        double totalTime = (endTime - startTime) / 1000.0;

        // Save metrics to CSV
        try {
            metricsCollector.writeToCSV("part2_metrics.csv");
            log.info("Metrics saved to part2_metrics.csv");
        } catch (Exception e) {
            log.error("Error saving metrics: {}", e.getMessage());
        }

        // Generate statistics
        var stats = metricsCollector.generateReport();
        stats.print();

        // Generate throughput over time visualization
        generateThroughputChart(mainStart);

        // Print final summary
        printResults(totalTime, warmupTime, mainTime);
    }

    private void runWarmupPhase(String baseServerUrl) {
        // Warmup without metrics (same as Part 1)
        BlockingQueue<ChatMessage> messageQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);
        Thread generator = new Thread(new MessageGenerator(messageQueue, WARMUP_MESSAGES));
        generator.start();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < INITIAL_THREADS; i++) {
            Thread thread = new Thread(() -> {
                sendMessagesAndTerminate(baseServerUrl, messageQueue,
                        MESSAGES_PER_INITIAL_THREAD,
                        warmupSuccessCount, warmupErrorCount, true);
            });
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
    }

    private void runMainPhase(String baseServerUrl) {
        ExecutorService executor = Executors.newFixedThreadPool(OPTIMAL_THREADS);
        BlockingQueue<ChatMessage> messageQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);

        Thread generator = new Thread(new MessageGenerator(messageQueue, MAIN_PHASE_MESSAGES));
        generator.start();

        int messagesPerThread = MAIN_PHASE_MESSAGES / OPTIMAL_THREADS;
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < OPTIMAL_THREADS; i++) {
            final int threadMessages = (i == OPTIMAL_THREADS - 1) ?
                    MAIN_PHASE_MESSAGES - (messagesPerThread * (OPTIMAL_THREADS - 1)) :
                    messagesPerThread;

            Future<?> future = executor.submit(() -> {
                sendMessagesWithPersistentConnection(baseServerUrl, messageQueue,
                        threadMessages, mainSuccessCount, mainErrorCount, true);
            });
            futures.add(future);
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("Error: {}", e.getMessage());
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
                                          boolean collectMetrics) {
        ChatWebSocketClient client = null;
        String roomId = String.valueOf(random.nextInt(20) + 1);
        Semaphore inFlightSemaphore = new Semaphore(MAX_IN_FLIGHT_PER_CONNECTION);

        try {
            String wsUrl = baseServerUrl.endsWith("/") ? baseServerUrl + roomId : baseServerUrl + "/" + roomId;
            URI serverUri = new URI(wsUrl);
            CountDownLatch latch = new CountDownLatch(messageCount);
            client = new ChatWebSocketClient(serverUri, latch, successCount, errorCount, inFlightSemaphore);

            // Enable metrics if requested
            if (collectMetrics) {
                client.enableMetrics(metricsCollector, roomId);
            }

            totalConnections.incrementAndGet();

            if (!client.connectBlocking(10, TimeUnit.SECONDS)) {
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

                String jsonMessage = objectMapper.writeValueAsString(message);
                boolean sent = sendWithRetry(client, jsonMessage, latch, errorCount, inFlightSemaphore);

                if (!sent) {
                    inFlightSemaphore.release();
                }
            }

            latch.await(120, TimeUnit.SECONDS);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
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
                                                      boolean collectMetrics) {
        ChatWebSocketClient client = null;
        String roomId = String.valueOf(random.nextInt(20) + 1);
        Semaphore inFlightSemaphore = new Semaphore(MAX_IN_FLIGHT_PER_CONNECTION);

        try {
            String wsUrl = baseServerUrl.endsWith("/") ? baseServerUrl + roomId : baseServerUrl + "/" + roomId;
            URI serverUri = new URI(wsUrl);
            CountDownLatch latch = new CountDownLatch(messageCount);
            client = new ChatWebSocketClient(serverUri, latch, successCount, errorCount, inFlightSemaphore);

            if (collectMetrics) {
                client.enableMetrics(metricsCollector, roomId);
            }

            totalConnections.incrementAndGet();

            if (!client.connectBlocking(10, TimeUnit.SECONDS)) {
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

                String jsonMessage = objectMapper.writeValueAsString(message);
                boolean sent = sendWithRetry(client, jsonMessage, latch, errorCount, inFlightSemaphore);

                if (!sent) {
                    inFlightSemaphore.release();
                }
            }

            latch.await(180, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
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

    private boolean sendWithRetry(ChatWebSocketClient client, String jsonMessage,
                                  CountDownLatch latch, AtomicInteger errorCount,
                                  Semaphore semaphore) {
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
                }
                try {
                    Thread.sleep((long) Math.pow(2, retry) * 100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Generate throughput over time visualization
     * Groups messages into 10-second buckets
     */
    private void generateThroughputChart(long testStartTime) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("THROUGHPUT OVER TIME (10-second intervals)");
        System.out.println("=".repeat(60));

        var metrics = metricsCollector.getMetrics();
        if (metrics.isEmpty()) {
            System.out.println("No metrics collected");
            return;
        }

        // Group messages by 10-second time buckets
        long bucketSizeMs = 10000; // 10 seconds
        Map<Long, Integer> timeBuckets = new TreeMap<>();

        for (var metric : metrics) {
            long elapsedTime = metric.getReceiveTime() - testStartTime;
            long bucket = elapsedTime / bucketSizeMs;
            timeBuckets.put(bucket, timeBuckets.getOrDefault(bucket, 0) + 1);
        }

        // Print as text chart
        long maxCount = timeBuckets.values().stream().max(Integer::compareTo).orElse(1);

        for (Map.Entry<Long, Integer> entry : timeBuckets.entrySet()) {
            long bucket = entry.getKey();
            int count = entry.getValue();
            double throughput = count / 10.0; // Messages per second

            // Create bar
            int barLength = (int) ((count * 50.0) / maxCount);
            String bar = "█".repeat(Math.max(1, barLength));

            System.out.printf("%3d-%3ds: %6.1f msg/s │%s%n",
                    bucket * 10, (bucket + 1) * 10,
                    throughput, bar);
        }

        System.out.println("=".repeat(60));

        // Save data for graphing
        saveThroughputData(timeBuckets, testStartTime);
    }

    /**
     * Save throughput data to CSV for visualization
     */
    private void saveThroughputData(Map<Long, Integer> timeBuckets, long startTime) {
        try (PrintWriter writer = new PrintWriter(new FileWriter("throughput_over_time.csv"))) {
            writer.println("TimeInterval,StartTime,EndTime,MessageCount,Throughput");

            for (Map.Entry<Long, Integer> entry : timeBuckets.entrySet()) {
                long bucket = entry.getKey();
                int count = entry.getValue();
                double throughput = count / 10.0;

                writer.printf("%d-%d,%d,%d,%d,%.2f%n",
                        bucket * 10, (bucket + 1) * 10,
                        bucket * 10, (bucket + 1) * 10,
                        count, throughput);
            }

            log.info("\n Throughput data saved to throughput_over_time.csv");
        } catch (Exception e) {
            log.error("Error saving throughput data: {}", e.getMessage());
        }
    }

    private void printResults(double totalTime, double warmupTime, double mainTime) {
        int totalSuccess = warmupSuccessCount.get() + mainSuccessCount.get();
        int totalFailed = warmupErrorCount.get() + mainErrorCount.get();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("FINAL RESULTS - PART 2");
        System.out.println("=".repeat(60));

        System.out.println("\nPHASE BREAKDOWN:");
        System.out.printf("  Warmup:  %d msgs in %.2fs (%.2f msg/sec)%n",
                warmupSuccessCount.get(), warmupTime,
                warmupSuccessCount.get() / Math.max(warmupTime, 0.001));
        System.out.printf("  Main:    %d msgs in %.2fs (%.2f msg/sec)%n",
                mainSuccessCount.get(), mainTime,
                mainSuccessCount.get() / Math.max(mainTime, 0.001));

        System.out.println("\nOVERALL:");
        System.out.printf("  Total Successful: %d%n", totalSuccess);
        System.out.printf("  Total Failed:     %d%n", totalFailed);
        System.out.printf("  Total Runtime:    %.2f seconds%n", totalTime);
        System.out.printf("  Throughput:       %.2f msg/sec%n",
                totalSuccess / Math.max(totalTime, 0.001));

        System.out.println("\nCONNECTIONS:");
        System.out.printf("  Total: %d, Reconnections: %d%n",
                totalConnections.get(), reconnections.get());

        System.out.println("=".repeat(60));
    }

    // Delegate methods (same as LoadTestClient but with metrics flag)
    // ... [Include sendMessagesAndTerminate and sendMessagesWithPersistentConnection
    //      from your LoadTestClient with collectMetrics parameter]
}