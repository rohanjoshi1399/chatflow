package com.chatflow.server.service;

import com.chatflow.server.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class RoomManager {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebSocketWriteManager writeManager;  // FIXED: Use write manager for thread-safe writes

    // Map of roomId -> Set of WebSocket sessions
    // FIXED: Changed from CopyOnWriteArraySet to ConcurrentHashMap.newKeySet() for better performance
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
     * FIXED: Now removes empty sets to prevent memory leak
     */
    public void unregisterSession(WebSocketSession session) {
        String roomId = sessionToRoom.remove(session.getId());
        if (roomId != null) {
            Set<WebSocketSession> sessions = roomSessions.get(roomId);
            if (sessions != null) {
                sessions.remove(session);
                
                // FIXED: Clean up empty sets to prevent memory leak
                if (sessions.isEmpty()) {
                    roomSessions.remove(roomId, sessions);  // Atomic removal
                    log.debug("Removed empty session set for room {}", roomId);
                }
                
                log.debug("Removed session {} from room {}", session.getId(), roomId);
            }
        }
    }

    /**
     * Add a session to a specific room
     * FIXED: Now uses ConcurrentHashMap.newKeySet() instead of CopyOnWriteArraySet
     */
    public void addSessionToRoom(String roomId, WebSocketSession session) {
        // Remove from previous room if exists
        String previousRoom = sessionToRoom.get(session.getId());
        if (previousRoom != null && !previousRoom.equals(roomId)) {
            removeSessionFromRoom(previousRoom, session);
        }

        // Add to new room - FIXED: Using ConcurrentHashMap.newKeySet() for better performance
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionToRoom.put(session.getId(), roomId);
        
        log.debug("Added session {} to room {}", session.getId(), roomId);
    }

    /**
     * Remove a session from a specific room
     * FIXED: Now cleans up empty sets
     */
    public void removeSessionFromRoom(String roomId, WebSocketSession session) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            sessionToRoom.remove(session.getId());
            
            // FIXED: Remove empty set
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId, sessions);
                log.debug("Removed empty session set for room {}", roomId);
            }
            
            log.debug("Removed session {} from room {}", session.getId(), roomId);
        }
    }

    /**
     * Broadcast message to all sessions in a room
     * FIXED: Uses WebSocketWriteManager for thread-safe writes and returns BroadcastResult
     */
    public BroadcastResult broadcastToRoom(QueueMessage queueMessage) {
        String roomId = queueMessage.getRoomId();
        Set<WebSocketSession> sessions = roomSessions.get(roomId);

        int successCount = 0;
        int failureCount = 0;
        List<String> failedSessionIds = new ArrayList<>();

        if (sessions == null || sessions.isEmpty()) {
            log.debug("No active sessions in room {}, skipping broadcast", roomId);
            return new BroadcastResult(0, 0, failedSessionIds);
        }

        try {
            String messageJson = objectMapper.writeValueAsString(queueMessage);
            TextMessage textMessage = new TextMessage(messageJson);

            // Create a copy to avoid ConcurrentModificationException during iteration
            List<WebSocketSession> sessionList = new ArrayList<>(sessions);

            for (WebSocketSession session : sessionList) {
                if (session.isOpen()) {
                    try {
                        // FIXED: Use WebSocketWriteManager instead of direct write
                        // This prevents TEXT_PARTIAL_WRITING errors
                        boolean sent = writeManager.sendMessage(session, textMessage);
                        
                        if (sent) {
                            successCount++;
                            messagesBroadcast.incrementAndGet();
                        } else {
                            // Message dropped (queue full or session inactive)
                            failureCount++;
                            failedSessionIds.add(session.getId());
                            broadcastFailures.incrementAndGet();
                            log.warn("Failed to queue message for session {} in room {}", 
                                    session.getId(), roomId);
                        }
                        
                    } catch (Exception e) {
                        log.error("Error broadcasting to session {} in room {}: {}", 
                                session.getId(), roomId, e.getMessage());
                        failureCount++;
                        failedSessionIds.add(session.getId());
                        broadcastFailures.incrementAndGet();
                        
                        // Remove dead session
                        removeSessionFromRoom(roomId, session);
                    }
                } else {
                    log.warn("Session {} in room {} is not open, removing", session.getId(), roomId);
                    removeSessionFromRoom(roomId, session);
                    failureCount++;
                    failedSessionIds.add(session.getId());
                }
            }

            log.debug("Broadcast to room {}: success={}, failures={}, messageId={}", 
                    roomId, successCount, failureCount, queueMessage.getMessageId());

        } catch (Exception e) {
            log.error("Error broadcasting to room {}: {}", roomId, e.getMessage(), e);
        }

        return new BroadcastResult(successCount, failureCount, failedSessionIds);
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
     * Get number of active rooms (rooms with at least one session)
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

    /**
     * Result object for broadcast operations
     * Added to track broadcast success/failure
     */
    @Data
    public static class BroadcastResult {
        private final int successCount;
        private final int failureCount;
        private final List<String> failedSessionIds;
    }
}