package com.chatflow.server.handler;

import com.chatflow.server.model.ChatMessage;
import com.chatflow.server.model.QueueMessage;
import com.chatflow.server.service.SQSService;
import com.chatflow.server.service.RoomSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SQSService sqsService;

    @Autowired
    private RoomSessionManager roomManager;

    @Value("${server.id:server-1}")
    private String serverId;

    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesPublished = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = extractRoomId(session);
        log.info("WebSocket connection established: sessionId={}, roomId={}, remoteAddress={}",
                session.getId(), roomId, session.getRemoteAddress());

        // Register session with RoomManager
        if (roomId != null) {
            roomManager.addSessionToRoom(roomId, session);
            // Store roomId in session attributes for later use
            session.getAttributes().put("roomId", roomId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        messagesReceived.incrementAndGet();

        try {
            String payload = message.getPayload();
            ChatMessage chatMessage = objectMapper.readValue(payload, ChatMessage.class);

            // Get roomId from session (extracted from WebSocket path)
            String roomId = (String) session.getAttributes().get("roomId");

            if (roomId == null) {
                log.error("No roomId found in session attributes for session {}", session.getId());
                sendErrorResponse(session, "Invalid room");
                messagesFailed.incrementAndGet();
                return;
            }

            // Validate message
            if (!isValidMessage(chatMessage)) {
                log.warn("Invalid message received from session {}: {}", session.getId(), payload);
                sendErrorResponse(session, "Invalid message format");
                messagesFailed.incrementAndGet();
                return;
            }

            // Extract client IP
            String clientIp = getClientIp(session);

            // Create queue message
            QueueMessage queueMessage = new QueueMessage(
                    roomId,
                    chatMessage.getUserId(),
                    chatMessage.getUsername(),
                    chatMessage.getMessage(),
                    chatMessage.getMessageType(),
                    serverId,
                    clientIp
            );

            // Store userId in session for broadcast filtering
            session.getAttributes().put("userId", chatMessage.getUserId());
            sendAckResponse(session, chatMessage);

            // Publish to SQS
            boolean published = sqsService.publishMessage(queueMessage);

            if (published) {
                messagesPublished.incrementAndGet();
                log.debug("Message published to queue: roomId={}, messageId={}",
                        queueMessage.getRoomId(), queueMessage.getMessageId());
            } else {
                messagesFailed.incrementAndGet();
                log.error("Failed to publish message to queue: roomId={}", queueMessage.getRoomId());
                sendErrorResponse(session, "Failed to queue message");
            }

        } catch (Exception e) {
            log.error("Error processing message from session {}: {}", session.getId(), e.getMessage(), e);
            messagesFailed.incrementAndGet();
            sendErrorResponse(session, "Internal server error");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String roomId = (String) session.getAttributes().get("roomId");
        log.info("WebSocket connection closed: sessionId={}, roomId={}, status={}",
                session.getId(), roomId, status);

        // Unregister session from RoomManager
        roomManager.unregisterSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
        roomManager.unregisterSession(session);
    }

    /**
     * Extract room ID from WebSocket path
     * Expected path format: /chat/{roomId}
     */
    private String extractRoomId(WebSocketSession session) {
        try {
            URI uri = session.getUri();
            if (uri != null) {
                String path = uri.getPath();
                // Path format: /chat/1, /chat/2, etc.
                String[] parts = path.split("/");
                if (parts.length >= 3) {
                    String roomId = parts[2];
                    // Validate roomId is 1-20
                    int room = Integer.parseInt(roomId);
                    if (room >= 1 && room <= 20) {
                        return roomId;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error extracting roomId from session {}: {}", session.getId(), e.getMessage());
        }
        return null;
    }

    /**
     * Validate incoming message
     */
    private boolean isValidMessage(ChatMessage message) {
        if (message == null) {
            return false;
        }

        // Check userId (1-100000)
        if (message.getUserId() == null || message.getUserId().trim().isEmpty()) {
            return false;
        }

        // Check username (3-20 chars)
        if (message.getUsername() == null ||
                message.getUsername().length() < 3 ||
                message.getUsername().length() > 20) {
            return false;
        }

        // Check message (1-500 chars)
        if (message.getMessage() == null ||
                message.getMessage().isEmpty() ||
                message.getMessage().length() > 500) {
            return false;
        }

        // Check timestamp (should be present)
        if (message.getTimestamp() == null || message.getTimestamp().trim().isEmpty()) {
            return false;
        }

        // Check messageType (TEXT, JOIN, or LEAVE)
        if (message.getMessageType() != null) {
            String type = message.getMessageType().toString().toUpperCase();
            return type.equals("TEXT") || type.equals("JOIN") || type.equals("LEAVE");
        } else {
            return false;
        }

    }

    /**
     * Send error response to client
     */
    private void sendErrorResponse(WebSocketSession session, String errorMessage) {
        try {
            ChatMessage errorResponse = new ChatMessage();
            errorResponse.setMessageType(ChatMessage.MessageType.TEXT);
            errorResponse.setMessage(errorMessage);
            errorResponse.setUserId("system");
            errorResponse.setUsername("system");
            errorResponse.setTimestamp(java.time.Instant.now().toString());

            String jsonResponse = objectMapper.writeValueAsString(errorResponse);
            session.sendMessage(new TextMessage(jsonResponse));
        } catch (Exception e) {
            log.error("Failed to send error response to session {}: {}", session.getId(), e.getMessage());
        }
    }

    /**
     * Extract client IP from session
     */
    private String getClientIp(WebSocketSession session) {
        try {
            InetSocketAddress remoteAddress = session.getRemoteAddress();
            if (remoteAddress != null) {
                return remoteAddress.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            log.warn("Failed to extract client IP: {}", e.getMessage());
        }
        return "unknown";
    }

    private void sendAckResponse(WebSocketSession session, ChatMessage chatMessage) {
        try {
            Map<String, Object> ack = new HashMap<>();
            ack.put("status", "SUCCESS");
            ack.put("originalMessage", chatMessage);

            String jsonResponse = objectMapper.writeValueAsString(ack);
            session.sendMessage(new TextMessage(jsonResponse));
        } catch (Exception e) {
            log.error("Failed to send ACK: {}", e.getMessage());
        }
    }

    /**
     * Get metrics for monitoring
     */
    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getMessagesPublished() {
        return messagesPublished.get();
    }

    public long getMessagesFailed() {
        return messagesFailed.get();
    }
}