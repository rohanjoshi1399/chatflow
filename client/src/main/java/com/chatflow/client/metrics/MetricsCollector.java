package com.chatflow.client.metrics;

import com.chatflow.client.model.MessageMetrics;
import com.chatflow.client.report.StatisticsReport;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MetricsCollector {
    // Use arrays for faster access (pre-allocated)
    private final long[] sendTimes;
    private final long[] receiveTimes;
    private final AtomicInteger metricsIndex;
    private final int capacity;

    private final Map<String, AtomicInteger> roomThroughput;
    private final Map<String, AtomicInteger> messageTypeDistribution;

    public MetricsCollector() {
        this(500000); // Default capacity
    }

    public MetricsCollector(int capacity) {
        this.capacity = capacity;
        this.sendTimes = new long[capacity];
        this.receiveTimes = new long[capacity];
        this.metricsIndex = new AtomicInteger(0);
        this.roomThroughput = new ConcurrentHashMap<>();
        this.messageTypeDistribution = new ConcurrentHashMap<>();
    }

    public void recordMetric(long sendTime, long receiveTime, String messageType,
                             int statusCode, String roomId) {
        int index = metricsIndex.getAndIncrement();

        if (index < capacity) {
            sendTimes[index] = sendTime;
            receiveTimes[index] = receiveTime;
        }

        // Use AtomicInteger for thread-safe increments (faster than merge)
        roomThroughput.computeIfAbsent(roomId, k -> new AtomicInteger()).incrementAndGet();
        messageTypeDistribution.computeIfAbsent(messageType, k -> new AtomicInteger()).incrementAndGet();
    }

    public void writeToCSV(String filename) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename), 65536)) {
            writer.write("timestamp,latency");
            writer.newLine();

            int count = Math.min(metricsIndex.get(), capacity);
            for (int i = 0; i < count; i++) {
                long latency = receiveTimes[i] - sendTimes[i];
                writer.write(String.format("%d,%d", sendTimes[i], latency));
                writer.newLine();
            }
        }
    }

    public StatisticsReport generateReport() {
        int count = Math.min(metricsIndex.get(), capacity);
        if (count == 0) {
            return new StatisticsReport();
        }

        long[] latencies = new long[count];
        for (int i = 0; i < count; i++) {
            latencies[i] = receiveTimes[i] - sendTimes[i];
        }

        Arrays.sort(latencies);

        long sum = 0;
        for (long latency : latencies) {
            sum += latency;
        }

        double mean = (double) sum / count;
        long median = latencies[count / 2];
        long p95 = latencies[(int) (count * 0.95)];
        long p99 = latencies[(int) (count * 0.99)];
        long min = latencies[0];
        long max = latencies[count - 1];

        // Convert AtomicInteger maps to regular maps
        Map<String, Integer> roomMap = new HashMap<>();
        roomThroughput.forEach((k, v) -> roomMap.put(k, v.get()));

        Map<String, Integer> typeMap = new HashMap<>();
        messageTypeDistribution.forEach((k, v) -> typeMap.put(k, v.get()));

        return new StatisticsReport(mean, median, p95, p99, min, max, roomMap, typeMap);
    }

    public List<MessageMetrics> getMetrics() {
        int count = Math.min(metricsIndex.get(), capacity);
        List<MessageMetrics> result = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            result.add(new MessageMetrics(sendTimes[i], receiveTimes[i],
                    "", 200, ""));
        }

        return result;
    }
}