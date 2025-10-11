package com.chatflow.client.test;

import com.chatflow.client.ChatWebSocketClient;
import com.chatflow.client.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test different queue sizes to find optimal configuration
 */
public class QueueSizeOptimizationTest {
    private static final String SERVER_URL = "ws://localhost:8080/chat/";
    private static final int TEST_MESSAGES = 50000;
    private static final int OPTIMAL_THREADS = 64; // Use already determined optimal
    private static final int MAX_RETRIES = 5;
    private static final int ITERATIONS = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    public static void main(String[] args) {
        QueueSizeOptimizationTest test = new QueueSizeOptimizationTest();

        // Test different queue sizes
        int[] queueSizes = {
                100,      // Very small
                500,      // Small
                1000,     // Small-medium
                2000,     // Medium
                5000,     // Medium-large
                10000,    // Large (current default)
                20000,    // Very large
                50000     // Extremely large
        };

        System.out.println("=".repeat(80));
        System.out.println("QUEUE SIZE OPTIMIZATION TEST");
        System.out.println("Testing with " + OPTIMAL_THREADS + " threads (already optimized)");
        System.out.println("Messages: " + TEST_MESSAGES);
        System.out.println("Iterations per queue size: " + ITERATIONS);
        System.out.println("=".repeat(80));

        List<QueueTestResult> results = new ArrayList<>();

        for (int queueSize : queueSizes) {
            System.out.println("\n--- Testing Queue Size: " + queueSize + " ---");

            List<SingleRunResult> runs = new ArrayList<>();

            for (int i = 1; i <= ITERATIONS; i++) {
                System.out.print("  Iteration " + i + "/" + ITERATIONS + "...");
                SingleRunResult result = test.runTest(queueSize);
                runs.add(result);
                System.out.printf(" %.2f msg/s, %d blocks, %.1f%% avg usage%n",
                        result.throughput, result.generatorBlocks,
                        result.avgQueueUtilization);

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            QueueTestResult aggregated = test.aggregateResults(queueSize, runs);
            results.add(aggregated);

            System.out.printf("  Average: %.2f msg/s, %.1f blocks, %.1f%% avg usage%n",
                    aggregated.avgThroughput,
                    aggregated.avgGeneratorBlocks,
                    aggregated.avgAvgUtilization);

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        test.printSummary(results);
        test.saveToCSV(results, "queue_optimization_results.csv");
    }

    private SingleRunResult runTest(int queueSize) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicLong generatorBlockCount = new AtomicLong(0);
        AtomicLong totalQueueSizeSamples = new AtomicLong(0);
        AtomicLong queueSizeSampleCount = new AtomicLong(0);
        AtomicInteger maxQueueSize = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(OPTIMAL_THREADS);
        BlockingQueue<ChatMessage> messageQueue = new LinkedBlockingQueue<>(queueSize);

        // Background thread to periodically sample queue size
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> {
            int currentSize = messageQueue.size();
            totalQueueSizeSamples.addAndGet(currentSize);
            queueSizeSampleCount.incrementAndGet();
            maxQueueSize.updateAndGet(max -> Math.max(max, currentSize));
        }, 0, 10, TimeUnit.MILLISECONDS); // Sample every 10ms

        // Modified generator that tracks blocking properly
        Thread generator = new Thread(() -> {
            try {
                for (int i = 0; i < TEST_MESSAGES; i++) {
                    ChatMessage msg = createMessage();

                    // Check if offer would block
                    boolean offered = false;

                    // Try to offer without blocking first
                    if (messageQueue.remainingCapacity() > 0) {
                        offered = messageQueue.offer(msg);
                    } else {
                        // Queue is full, this will block
                        generatorBlockCount.incrementAndGet();
                        offered = messageQueue.offer(msg, 30, TimeUnit.SECONDS);
                    }

                    if (!offered) {
                        System.err.println("Failed to add message to queue!");
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        generator.start();

        // Give generator a small head start
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Start consumers
        int messagesPerThread = TEST_MESSAGES / OPTIMAL_THREADS;
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < OPTIMAL_THREADS; i++) {
            final int threadMessages = (i == OPTIMAL_THREADS - 1) ?
                    TEST_MESSAGES - (messagesPerThread * (OPTIMAL_THREADS - 1)) :
                    messagesPerThread;

            Future<?> future = executor.submit(() -> {
                sendMessages(messageQueue, threadMessages, successCount, errorCount);
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

        // Stop sampling
        sampler.shutdown();
        try {
            sampler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long endTime = System.currentTimeMillis();
        double duration = (endTime - startTime) / 1000.0;
        double throughput = TEST_MESSAGES / duration;

        // Calculate average queue utilization
        double avgQueueSize = queueSizeSampleCount.get() > 0
                ? (double) totalQueueSizeSamples.get() / queueSizeSampleCount.get()
                : 0;
        double avgQueueUtilization = (avgQueueSize / queueSize) * 100.0;
        double peakQueueUtilization = (maxQueueSize.get() * 100.0) / queueSize;

        return new SingleRunResult(
                queueSize,
                throughput,
                duration,
                successCount.get(),
                errorCount.get(),
                generatorBlockCount.get(),
                maxQueueSize.get(),
                peakQueueUtilization,
                avgQueueUtilization
        );
    }

    private ChatMessage createMessage() {
        String userId = String.valueOf(random.nextInt(100000) + 1);
        String username = "user" + userId;

        String[] messages = {
                "Hello everyone!", "How are you doing?", "Great weather today!",
                "Anyone up for coffee?", "Working on exciting project",
                "Happy Monday!", "TGIF!", "Good morning team"
        };
        String message = messages[random.nextInt(messages.length)];

        String timestamp = java.time.Instant.now().toString();

        // Generate messageType as ENUM
        ChatMessage.MessageType messageType;
        int roll = random.nextInt(100);
        if (roll < 90) {
            messageType = ChatMessage.MessageType.TEXT;      // 90% TEXT
        } else if (roll < 95) {
            messageType = ChatMessage.MessageType.JOIN;      // 5% JOIN
        } else {
            messageType = ChatMessage.MessageType.LEAVE;     // 5% LEAVE
        }

        return new ChatMessage(userId, username, message, timestamp, messageType);
    }

    private void sendMessages(BlockingQueue<ChatMessage> messageQueue,
                              int messageCount,
                              AtomicInteger successCount,
                              AtomicInteger errorCount) {
        ChatWebSocketClient client = null;
        String roomId = String.valueOf(random.nextInt(20) + 1);

        try {
            String wsUrl = SERVER_URL + roomId;
            URI serverUri = new URI(wsUrl);
            CountDownLatch latch = new CountDownLatch(messageCount);
            client = new ChatWebSocketClient(serverUri, latch, successCount, errorCount);

            if (!client.connectBlocking(10, TimeUnit.SECONDS)) {
                return;
            }

            Thread.sleep(50);

            for (int i = 0; i < messageCount; i++) {
                ChatMessage message = messageQueue.poll(30, TimeUnit.SECONDS);
                if (message == null) break;

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
                        } else {
                            Thread.sleep((long) Math.pow(2, retry) * 100);
                        }
                    }
                }
            }

            latch.await(120, TimeUnit.SECONDS);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            if (client != null && client.isOpen()) {
                client.close();
            }
        }
    }

    private QueueTestResult aggregateResults(int queueSize, List<SingleRunResult> runs) {
        double sumThroughput = 0;
        double sumBlocks = 0;
        double sumPeakUtilization = 0;
        double sumAvgUtilization = 0;

        for (SingleRunResult run : runs) {
            sumThroughput += run.throughput;
            sumBlocks += run.generatorBlocks;
            sumPeakUtilization += run.peakQueueUtilization;
            sumAvgUtilization += run.avgQueueUtilization;
        }

        return new QueueTestResult(
                queueSize,
                sumThroughput / runs.size(),
                sumBlocks / runs.size(),
                sumPeakUtilization / runs.size(),
                sumAvgUtilization / runs.size()
        );
    }

    private void printSummary(List<QueueTestResult> results) {
        System.out.println("\n" + "=".repeat(90));
        System.out.println("QUEUE SIZE OPTIMIZATION SUMMARY");
        System.out.println("=".repeat(90));
        System.out.printf("%-10s %-16s %-10s %-14s %-14s %-20s%n",
                "Queue", "Throughput", "Blocks",
                "Peak %", "Avg %", "Status");
        System.out.println("-".repeat(90));

        QueueTestResult best = results.get(0);
        for (QueueTestResult result : results) {
            if (result.avgThroughput > best.avgThroughput) {
                best = result;
            }
        }

        for (QueueTestResult result : results) {
            System.out.printf("%-10d %-16.2f %-10.1f %-14.2f %-14.2f ",
                    result.queueSize, result.avgThroughput,
                    result.avgGeneratorBlocks,
                    result.avgPeakUtilization,
                    result.avgAvgUtilization);

            // Better status determination
            String status = determineQueueStatus(result);
            System.out.print(status);

            if (result == best) {
                System.out.print(" <- BEST");
            }

            System.out.println();
        }

        System.out.println("=".repeat(90));
        System.out.println("\nANALYSIS:");

        // Check if all queues show high utilization
        boolean allHighUtilization = true;
        for (QueueTestResult result : results) {
            if (result.avgAvgUtilization < 60) {
                allHighUtilization = false;
                break;
            }
        }

        if (allHighUtilization) {
            System.out.println("  ⚠️  All queues show high utilization (>60% average)");
            System.out.println("  This indicates: Consumer threads are slower than producer");
            System.out.println("  Root cause: Network I/O is the bottleneck, not queue size");
            System.out.println();
            System.out.println("  Key insight: Queue size has MINIMAL impact on throughput");
            System.out.println("  Best queue based on: Lowest blocking + good throughput");
        }

        System.out.println("\nOPTIMAL CONFIGURATION:");
        System.out.printf("  Queue Size: %d messages%n", best.queueSize);
        System.out.printf("  Throughput: %.2f msg/s%n", best.avgThroughput);
        System.out.printf("  Generator Blocks: %.0f times%n", best.avgGeneratorBlocks);
        System.out.printf("  Block Rate: %.2f%% of messages%n",
                (best.avgGeneratorBlocks / TEST_MESSAGES * 100));
        System.out.printf("  Average Queue Usage: %.1f%%%n", best.avgAvgUtilization);

        // Find queue with minimum blocks
        QueueTestResult minBlocks = results.get(0);
        for (QueueTestResult result : results) {
            if (result.avgGeneratorBlocks < minBlocks.avgGeneratorBlocks) {
                minBlocks = result;
            }
        }

        System.out.println("\nRECOMMENDATION:");
        if (best == minBlocks) {
            System.out.println("  ✅ Best throughput AND lowest blocking - perfect choice!");
        } else {
            System.out.printf("  Alternative: Queue size %d has fewer blocks (%.0f) " +
                            "with similar throughput (%.2f msg/s)%n",
                    minBlocks.queueSize, minBlocks.avgGeneratorBlocks,
                    minBlocks.avgThroughput);
        }

        // Practical recommendation
        System.out.println("\n  PRACTICAL CHOICE:");
        if (best.queueSize < 5000) {
            System.out.printf("  Use queue size: %d%n", best.queueSize);
            System.out.println("  Reason: Best throughput with minimal memory overhead");
        } else {
            System.out.println("  Use queue size: 2000-5000");
            System.out.println("  Reason: Good balance between blocking and memory usage");
        }

        System.out.println("=".repeat(90));
    }

    private String determineQueueStatus(QueueTestResult result) {
        double avgUtil = result.avgAvgUtilization;
        double blocks = result.avgGeneratorBlocks;
        double blockRate = (blocks / TEST_MESSAGES) * 100;

        if (avgUtil > 85) {
            return "Very High Usage";
        } else if (avgUtil > 70) {
            return "High Usage";
        } else if (avgUtil > 50) {
            return "Moderate Usage";
        } else if (avgUtil > 30) {
            return "Low Usage";
        } else {
            return "Very Low Usage";
        }
    }

    private void saveToCSV(List<QueueTestResult> results, String filename) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("QueueSize,AvgThroughput,AvgGeneratorBlocks,AvgPeakUtilization,AvgAvgUtilization");
            for (QueueTestResult result : results) {
                writer.printf("%d,%.2f,%.2f,%.2f,%.2f%n",
                        result.queueSize, result.avgThroughput,
                        result.avgGeneratorBlocks, result.avgPeakUtilization,
                        result.avgAvgUtilization);
            }
            System.out.println("\nResults saved to " + filename);
        } catch (Exception e) {
            System.err.println("Error saving CSV: " + e.getMessage());
        }
    }

    static class SingleRunResult {
        int queueSize;
        double throughput;
        double duration;
        int successCount;
        int errorCount;
        long generatorBlocks;
        int maxQueueSize;
        double peakQueueUtilization;
        double avgQueueUtilization;

        SingleRunResult(int queueSize, double throughput, double duration,
                        int successCount, int errorCount, long generatorBlocks,
                        int maxQueueSize, double peakQueueUtilization,
                        double avgQueueUtilization) {
            this.queueSize = queueSize;
            this.throughput = throughput;
            this.duration = duration;
            this.successCount = successCount;
            this.errorCount = errorCount;
            this.generatorBlocks = generatorBlocks;
            this.maxQueueSize = maxQueueSize;
            this.peakQueueUtilization = peakQueueUtilization;
            this.avgQueueUtilization = avgQueueUtilization;
        }
    }

    static class QueueTestResult {
        int queueSize;
        double avgThroughput;
        double avgGeneratorBlocks;
        double avgPeakUtilization;
        double avgAvgUtilization;

        QueueTestResult(int queueSize, double avgThroughput,
                        double avgGeneratorBlocks, double avgPeakUtilization,
                        double avgAvgUtilization) {
            this.queueSize = queueSize;
            this.avgThroughput = avgThroughput;
            this.avgGeneratorBlocks = avgGeneratorBlocks;
            this.avgPeakUtilization = avgPeakUtilization;
            this.avgAvgUtilization = avgAvgUtilization;
        }
    }
}