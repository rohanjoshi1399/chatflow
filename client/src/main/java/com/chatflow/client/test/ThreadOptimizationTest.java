package com.chatflow.client.test;

import com.chatflow.client.ChatWebSocketClient;
import com.chatflow.client.model.ChatMessage;
import com.chatflow.client.generator.MessageGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test different thread configurations to find optimal thread count
 * Runs multiple iterations for statistical reliability
 */
public class ThreadOptimizationTest {
    private static final String SERVER_URL = "ws://localhost:8080/chat/";
    private static final int TEST_MESSAGES = 50000;
    private static final int MAX_RETRIES = 5;
    private static final int ITERATIONS_PER_CONFIG = 3;
    private static final int MAX_IN_FLIGHT_PER_CONNECTION = 50; // Backpressure control
    private static final int QUEUE_SIZE = 2000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    public static void main(String[] args) {
//        String serverUrl = args.length > 0 ? args[0] : SERVER_URL;
        String serverUrl = SERVER_URL;
        ThreadOptimizationTest test = new ThreadOptimizationTest();

        // Test with powers of 2 and strategic midpoints
        int[] threadCounts = {8, 16, 24, 32, 48, 64, 96, 128, 192, 256};

        System.out.println("=".repeat(80));
        System.out.println("THREAD OPTIMIZATION TEST");
        System.out.println("=".repeat(80));
        System.out.println("Server URL: " + serverUrl);
        System.out.println("Test Messages: " + TEST_MESSAGES + " per configuration");
        System.out.println("Iterations: " + ITERATIONS_PER_CONFIG + " per configuration");
        System.out.println("Backpressure: " + MAX_IN_FLIGHT_PER_CONNECTION + " max in-flight msgs/connection");
        System.out.println("=".repeat(80));

        List<AggregatedTestResult> results = new ArrayList<>();

        for (int threadCount : threadCounts) {
            System.out.println("\n--- Testing with " + threadCount + " threads ---");

            List<TestResult> iterations = new ArrayList<>();

            // Run multiple iterations
            for (int i = 1; i <= ITERATIONS_PER_CONFIG; i++) {
                System.out.print("  Iteration " + i + "/" + ITERATIONS_PER_CONFIG + "...");
                TestResult result = test.runTest(threadCount, serverUrl);
                iterations.add(result);
                System.out.printf(" %.2f msg/s (%.2fs)%n", result.throughput, result.duration);

                // Delay between iterations
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Aggregate results
            AggregatedTestResult aggregated = test.aggregateResults(threadCount, iterations);
            results.add(aggregated);

            System.out.printf("  Average: %.2f msg/s (σ=%.2f, CV=%.2f%%)%n",
                    aggregated.avgThroughput, aggregated.stdDevThroughput,
                    (aggregated.stdDevThroughput / aggregated.avgThroughput * 100));

            // Delay before next thread configuration
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Print summary
        test.printSummary(results);

        // Save to CSV
        test.saveToCSV(results, "thread_optimization_results.csv");
    }

    private TestResult runTest(int threadCount, String serverUrl) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        BlockingQueue<ChatMessage> messageQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);

        // Start message generator concurrently
        Thread generator = new Thread(new MessageGenerator(messageQueue, TEST_MESSAGES));
        generator.start();

        int messagesPerThread = TEST_MESSAGES / threadCount;
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadMessages = (i == threadCount - 1) ?
                    TEST_MESSAGES - (messagesPerThread * (threadCount - 1)) :
                    messagesPerThread;

            Future<?> future = executor.submit(() -> {
                sendMessages(serverUrl, messageQueue, threadMessages,
                        successCount, errorCount);
            });
            futures.add(future);
        }

        // Wait for completion
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                System.err.println("Thread error: " + e.getMessage());
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long endTime = System.currentTimeMillis();
        double duration = (endTime - startTime) / 1000.0;
        double throughput = successCount.get() / duration; // Use actual success count

        return new TestResult(threadCount, successCount.get(), errorCount.get(),
                duration, throughput);
    }

    private void sendMessages(String baseServerUrl,
                              BlockingQueue<ChatMessage> messageQueue,
                              int messageCount,
                              AtomicInteger successCount,
                              AtomicInteger errorCount) {
        ChatWebSocketClient client = null;
        String roomId = String.valueOf(random.nextInt(20) + 1);
        Semaphore inFlightSemaphore = new Semaphore(MAX_IN_FLIGHT_PER_CONNECTION);

        try {
            String wsUrl = baseServerUrl.endsWith("/") ? baseServerUrl + roomId : baseServerUrl + roomId;
            URI serverUri = new URI(wsUrl);
            CountDownLatch latch = new CountDownLatch(messageCount);

            // Use backpressure-enabled client
            client = new ChatWebSocketClient(serverUri, latch, successCount, errorCount, inFlightSemaphore);

            if (!client.connectBlocking(10, TimeUnit.SECONDS)) {
                System.err.println("Failed to connect");
                for (int i = 0; i < messageCount; i++) {
                    errorCount.incrementAndGet();
                }
                return;
            }

            Thread.sleep(100);

            // Send messages with backpressure control
            for (int i = 0; i < messageCount; i++) {
                ChatMessage message = messageQueue.poll(30, TimeUnit.SECONDS);
                if (message == null) {
                    latch.countDown();
                    errorCount.incrementAndGet();
                    continue;
                }

                // Wait for permit (backpressure)
                if (!inFlightSemaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                    latch.countDown();
                    errorCount.incrementAndGet();
                    continue;
                }

                String jsonMessage = objectMapper.writeValueAsString(message);
                boolean sent = false;

                for (int retry = 0; retry < MAX_RETRIES && !sent; retry++) {
                    try {
                        if (!client.isOpen()) {
                            client.reconnectBlocking();
                            Thread.sleep(50);
                        }
                        client.sendMessageWithTracking(jsonMessage);
                        sent = true;
                    } catch (Exception e) {
                        if (retry == MAX_RETRIES - 1) {
                            errorCount.incrementAndGet();
                            latch.countDown();
                            inFlightSemaphore.release(); // Release on failure
                        } else {
                            Thread.sleep((long) Math.pow(2, retry) * 100);
                        }
                    }
                }

                if (!sent) {
                    inFlightSemaphore.release();
                }
            }

            // Wait for all responses
            if (!latch.await(120, TimeUnit.SECONDS)) {
                long remaining = latch.getCount();
                for (int i = 0; i < remaining; i++) {
                    errorCount.incrementAndGet();
                }
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            if (client != null && client.isOpen()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                client.close();
            }
        }
    }

    private AggregatedTestResult aggregateResults(int threadCount,
                                                  List<TestResult> iterations) {
        double sumThroughput = 0;
        double sumDuration = 0;
        int totalSuccess = 0;
        int totalErrors = 0;

        for (TestResult result : iterations) {
            sumThroughput += result.throughput;
            sumDuration += result.duration;
            totalSuccess += result.successCount;
            totalErrors += result.errorCount;
        }

        double avgThroughput = sumThroughput / iterations.size();
        double avgDuration = sumDuration / iterations.size();

        // Calculate standard deviation
        double sumSquaredDiff = 0;
        for (TestResult result : iterations) {
            double diff = result.throughput - avgThroughput;
            sumSquaredDiff += diff * diff;
        }
        double stdDev = Math.sqrt(sumSquaredDiff / iterations.size());

        return new AggregatedTestResult(
                threadCount,
                avgThroughput,
                stdDev,
                avgDuration,
                totalSuccess / iterations.size(),
                totalErrors / iterations.size()
        );
    }

    private void printSummary(List<AggregatedTestResult> results) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("OPTIMIZATION TEST SUMMARY (Averaged over " + ITERATIONS_PER_CONFIG + " runs)");
        System.out.println("=".repeat(80));
        System.out.printf("%-12s %-18s %-15s %-15s %-12s%n",
                "Threads", "Avg Throughput", "Std Dev", "Avg Duration", "Errors");
        System.out.println("-".repeat(80));

        // Find the best result
        AggregatedTestResult best = results.get(0);
        for (AggregatedTestResult result : results) {
            if (result.avgThroughput > best.avgThroughput) {
                best = result;
            }
        }

        // Print all results
        for (AggregatedTestResult result : results) {
            System.out.printf("%-12d %-18.2f %-15.2f %-15.2f %-12d",
                    result.threadCount, result.avgThroughput,
                    result.stdDevThroughput, result.avgDuration,
                    result.avgErrors);

            if (result == best) {
                System.out.print(" <- BEST");
            }
            System.out.println();
        }

        System.out.println("=".repeat(80));
        System.out.println("\nOPTIMAL CONFIGURATION:");
        System.out.printf("  Threads: %d%n", best.threadCount);
        System.out.printf("  Average Throughput: %.2f messages/second%n", best.avgThroughput);
        System.out.printf("  Standard Deviation: %.2f (CV: %.2f%%)%n",
                best.stdDevThroughput,
                (best.stdDevThroughput / best.avgThroughput * 100));
        System.out.printf("  Average Duration: %.2f seconds%n", best.avgDuration);
        System.out.printf("  Average Errors: %d (%.2f%%)%n",
                best.avgErrors, (best.avgErrors * 100.0 / TEST_MESSAGES));
        System.out.printf("  Consistency: %s%n",
                best.stdDevThroughput / best.avgThroughput < 0.05 ?
                        "Excellent (<5% variance)" :
                        best.stdDevThroughput / best.avgThroughput < 0.10 ?
                                "Good (<10% variance)" : "Acceptable");

        System.out.println("\nRECOMMENDATION:");
        System.out.println("  Use " + best.threadCount + " threads for main phase in LoadTestClient");
        System.out.println("  Expected throughput: ~" + String.format("%.0f", best.avgThroughput) + " msg/sec");

        System.out.println("=".repeat(80));
    }

    private void saveToCSV(List<AggregatedTestResult> results, String filename) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("ThreadCount,AvgThroughput,StdDevThroughput,AvgDuration,AvgSuccess,AvgErrors");
            for (AggregatedTestResult result : results) {
                writer.printf("%d,%.2f,%.2f,%.2f,%d,%d%n",
                        result.threadCount, result.avgThroughput,
                        result.stdDevThroughput, result.avgDuration,
                        result.avgSuccess, result.avgErrors);
            }
            System.out.println("\nResults saved to " + filename);
        } catch (Exception e) {
            System.err.println("Error saving CSV: " + e.getMessage());
        }
    }

    static class TestResult {
        int threadCount;
        int successCount;
        int errorCount;
        double duration;
        double throughput;

        TestResult(int threadCount, int successCount, int errorCount,
                   double duration, double throughput) {
            this.threadCount = threadCount;
            this.successCount = successCount;
            this.errorCount = errorCount;
            this.duration = duration;
            this.throughput = throughput;
        }
    }

    static class AggregatedTestResult {
        int threadCount;
        double avgThroughput;
        double stdDevThroughput;
        double avgDuration;
        int avgSuccess;
        int avgErrors;

        AggregatedTestResult(int threadCount, double avgThroughput,
                             double stdDevThroughput, double avgDuration,
                             int avgSuccess, int avgErrors) {
            this.threadCount = threadCount;
            this.avgThroughput = avgThroughput;
            this.stdDevThroughput = stdDevThroughput;
            this.avgDuration = avgDuration;
            this.avgSuccess = avgSuccess;
            this.avgErrors = avgErrors;
        }
    }
}