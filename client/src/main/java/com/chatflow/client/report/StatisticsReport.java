package com.chatflow.client.report;

import lombok.Getter;

import java.util.Map;

@Getter
public class StatisticsReport {
    // Getters
    private double meanLatency;
    private long medianLatency;
    private long p95Latency;
    private long p99Latency;
    private long minLatency;
    private long maxLatency;
    private Map<String, Integer> roomThroughput;
    private Map<String, Integer> messageTypeDistribution;

    public StatisticsReport() {}

    public StatisticsReport(double mean, long median, long p95, long p99,
                            long min, long max,
                            Map<String, Integer> roomThroughput,
                            Map<String, Integer> messageTypeDistribution) {
        this.meanLatency = mean;
        this.medianLatency = median;
        this.p95Latency = p95;
        this.p99Latency = p99;
        this.minLatency = min;
        this.maxLatency = max;
        this.roomThroughput = roomThroughput;
        this.messageTypeDistribution = messageTypeDistribution;
    }

    public void print() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("PERFORMANCE STATISTICS");
        System.out.println("=".repeat(60));
        System.out.printf("Mean Response Time:   %.2f ms%n", meanLatency);
        System.out.printf("Median Response Time: %d ms%n", medianLatency);
        System.out.printf("95th Percentile:      %d ms%n", p95Latency);
        System.out.printf("99th Percentile:      %d ms%n", p99Latency);
        System.out.printf("Min Response Time:    %d ms%n", minLatency);
        System.out.printf("Max Response Time:    %d ms%n", maxLatency);

        System.out.println("\nTHROUGHPUT PER ROOM:");
        System.out.println("-".repeat(60));
        roomThroughput.forEach((room, count) ->
                System.out.printf("Room %s: %d messages%n", room, count));

        System.out.println("\nMESSAGE TYPE DISTRIBUTION:");
        System.out.println("-".repeat(60));
        messageTypeDistribution.forEach((type, count) ->
                System.out.printf("%s: %d messages%n", type, count));
        System.out.println("=".repeat(60) + "\n");
    }

}