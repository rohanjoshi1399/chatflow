package com.chatflow.server.listener;

import com.chatflow.server.service.ConnectionMetricsService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private final ConnectionMetricsService metricsService;

    public WebSocketEventListener(ConnectionMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        metricsService.incrementConnections();

        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();

        System.out.println("New WebSocket connection established.");
        System.out.println("Session ID: " + sessionId);
        System.out.println("Active connections: " + metricsService.getActiveConnections());
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        metricsService.decrementConnections();

        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();

        System.out.println("WebSocket connection closed.");
        System.out.println("Session ID: " + sessionId);
        System.out.println("Active connections: " + metricsService.getActiveConnections());
    }
}