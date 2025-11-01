package com.chatflow.server.service;

import com.chatflow.server.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class RoomSessionManager {

    @Autowired
    private ObjectMapper objectMapper;

    // Map of roomId -> Set of WebSocket sessions
    private final ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    // Map of sessionId -> roomId for quick lookup
    private final ConcurrentHashMap<String, String> sessionToRoom = new ConcurrentHashMap<>();

    private final AtomicLong messagesBroadcast = new AtomicLong(0);
    private final AtomicLong broadcastFailures = new AtomicLong(0);

    /**
     * Register a WebSocket session (initially not in any room)
     */
    public void registerSession(WebSocketSession session) {
        log.debug("Registered session: {}", session.getId());
    }

    /**
     * Unregister a WebSocket session from all rooms
     */
    public void unregisterSession(WebSocketSession session) {
        String roomId = sessionToRoom.remove(session.getId());
        if (roomId != null) {
            Set<WebSocketSession> sessions = roomSessions.get(roomId);
            if (sessions != null) {
                sessions.remove(session);
                log.debug("Removed session {} from room {}", session.getId(), roomId);
            }
        }
    }

    /**
     * Add a session to a specific room
     */
    public void addSessionToRoom(String roomId, WebSocketSession session) {
        // Remove from previous room if exists
        String previousRoom = sessionToRoom.get(session.getId());
        if (previousRoom != null && !previousRoom.equals(roomId)) {
            removeSessionFromRoom(previousRoom, session);
        }

        // Add to new room
        roomSessions.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>()).add(session);
        sessionToRoom.put(session.getId(), roomId);

        log.debug("Added session {} to room {}", session.getId(), roomId);
    }

    /**
     * Remove a session from a specific room
     */
    public void removeSessionFromRoom(String roomId, WebSocketSession session) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            sessionToRoom.remove(session.getId());
            log.debug("Removed session {} from room {}", session.getId(), roomId);
        }
    }

    /**
     * Broadcast message to all sessions in a room
     */
    public void broadcastToRoom(QueueMessage queueMessage) {
        String roomId = queueMessage.getRoomId();
        Set<WebSocketSession> sessions = roomSessions.get(roomId);

        if (sessions == null || sessions.isEmpty()) {
            log.debug("No active sessions in room {}, skipping broadcast", roomId);
            return;
        }

        try {
            String messageJson = objectMapper.writeValueAsString(queueMessage);
            TextMessage textMessage = new TextMessage(messageJson);

            int successCount = 0;
            int failureCount = 0;

            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            // Only broadcast to other users, not the sender
                            String senderUserId = queueMessage.getUserId();
                            String sessionUserId = (String) session.getAttributes().get("userId");

                            // Skip if this is the sender (they already get ACK after message is published to Queue)
                            if (senderUserId != null && senderUserId.equals(sessionUserId)) {
                                continue;
                            }

                            session.sendMessage(textMessage);
                        }
                        successCount++;
                        messagesBroadcast.incrementAndGet();
                    } catch (IOException e) {
                        log.error("Failed to send message to session {} in room {}: {}",
                                session.getId(), roomId, e.getMessage());
                        failureCount++;
                        broadcastFailures.incrementAndGet();

                        // Remove dead session
                        removeSessionFromRoom(roomId, session);
                    }
                } else {
                    log.warn("Session {} in room {} is not open, removing", session.getId(), roomId);
                    removeSessionFromRoom(roomId, session);
                    failureCount++;
                }
            }

            log.debug("Broadcast to room {}: success={}, failures={}, messageId={}",
                    roomId, successCount, failureCount, queueMessage.getMessageId());

        } catch (Exception e) {
            log.error("Error broadcasting to room {}: {}", roomId, e.getMessage(), e);
        }
    }

    /**
     * Get number of active sessions in a room
     */
    public int getRoomSessionCount(String roomId) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * Get total number of active sessions across all rooms
     */
    public int getTotalSessionCount() {
        return sessionToRoom.size();
    }

    /**
     * Get number of active rooms
     */
    public int getActiveRoomCount() {
        return roomSessions.size();
    }

    /**
     * Get metrics
     */
    public long getMessagesBroadcast() {
        return messagesBroadcast.get();
    }

    public long getBroadcastFailures() {
        return broadcastFailures.get();
    }

    /**
     * Get all rooms with their session counts
     */
    public ConcurrentHashMap<String, Integer> getRoomStats() {
        ConcurrentHashMap<String, Integer> stats = new ConcurrentHashMap<>();
        roomSessions.forEach((roomId, sessions) -> stats.put(roomId, sessions.size()));
        return stats;
    }
}