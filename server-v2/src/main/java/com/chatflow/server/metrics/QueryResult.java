package com.chatflow.server.metrics;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Result object for query performance tracking
 */
@Data
@AllArgsConstructor
public class QueryResult {
    private long executionTimeMs;
    private int resultCount;
    private Object sampleData;
}
