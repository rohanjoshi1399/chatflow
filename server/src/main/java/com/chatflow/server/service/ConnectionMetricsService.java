package com.chatflow.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ConnectionMetricsService {

    private final AtomicInteger activeConnections = new AtomicInteger(0);

    public void incrementConnections() {
        int count = activeConnections.incrementAndGet();
        System.out.println(">>> Connection added. Total: " + count);
    }

    public void decrementConnections() {
        int count = activeConnections.decrementAndGet();
        log.info("<<< Connection removed. Total: {}", count);
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }
}