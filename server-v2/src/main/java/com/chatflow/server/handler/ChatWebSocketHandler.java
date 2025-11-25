package com.chatflow.server.handler;

import com.chatflow.server.model.ChatMessage;
import com.chatflow.server.model.QueueMessage;
import com.chatflow.server.service.SQSService;
import com.chatflow.server.service.SQSBatchPublisher;
import com.chatflow.server.service.RoomManager;
import com.chatflow.server.service.WebSocketWriteManager;
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

    @Autowired(required = false)
    private SQSBatchPublisher batchPublisher;

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private WebSocketWriteManager writeManager;

    @Value("${server.id:server-1}")
    private String serverId;

    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesPublished = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    private final AtomicLong acksSent = new AtomicLong(0);
    private final AtomicLong acksFailed = new AtomicLong(0);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = extractRoomId(session);

        if (roomId == null) {
            log.warn("Invalid room ID in connection path: {}", session.getUri());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        log.info("WebSocket connection established: sessionId={}, roomId={}, remoteAddress={}",
                session.getId(), roomId, session.getRemoteAddress());

        session.getAttributes().put("roomId", roomId);

        writeManager.registerSession(session);
        roomManager.addSessionToRoom(roomId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        messagesReceived.incrementAndGet();

        try {
            String payload = message.getPayload();
            ChatMessage chatMessage = objectMapper.readValue(payload, ChatMessage.class);

            String roomId = (String) session.getAttributes().get("roomId");

            if (roomId == null) {
                log.error("No roomId found in session attributes for session {}", session.getId());
                sendErrorResponse(session, "Invalid room");
                messagesFailed.incrementAndGet();
                return;
            }

            if (!isValidMessage(chatMessage)) {
                log.warn("Invalid message received from session {}: {}", session.getId(), payload);
                sendErrorResponse(session, "Invalid message format");
                messagesFailed.incrementAndGet();
                return;
            }

            session.getAttributes().put("userId", chatMessage.getUserId());
            String clientIp = getClientIp(session);

            QueueMessage queueMessage = new QueueMessage(
                    roomId,
                    chatMessage.getUserId(),
                    chatMessage.getUsername(),
                    chatMessage.getMessage(),
                    chatMessage.getMessageType(),
                    serverId,
                    clientIp
            );

            boolean published;
            if (batchPublisher != null && batchPublisher.isEnabled()) {
                published = batchPublisher.publishMessage(queueMessage);
            } else {
                published = sqsService.publishMessage(queueMessage);
            }

            if (published) {
                messagesPublished.incrementAndGet();
                sendAckResponse(session, chatMessage);
                log.debug("Message published to queue: roomId={}, messageId={}",
                        roomId, queueMessage.getMessageId());
            } else {
                messagesFailed.incrementAndGet();
                log.error("Failed to publish message to queue: roomId={}", roomId);
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

        roomManager.unregisterSession(session);
        writeManager.unregisterSession(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
        roomManager.unregisterSession(session);
        writeManager.unregisterSession(session.getId());
    }

    private String extractRoomId(WebSocketSession session) {
        try {
            URI uri = session.getUri();
            if (uri != null) {
                String path = uri.getPath();
                String[] parts = path.split("/");
                if (parts.length >= 3) {
                    String roomId = parts[2];
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

    private boolean isValidMessage(ChatMessage message) {
        if (message == null) {
            return false;
        }

        if (message.getUserId() == null || message.getUserId().trim().isEmpty()) {
            return false;
        }

        if (message.getUsername() == null ||
                message.getUsername().length() < 3 ||
                message.getUsername().length() > 20) {
            return false;
        }

        if (message.getMessage() == null ||
                message.getMessage().length() < 1 ||
                message.getMessage().length() > 500) {
            return false;
        }

        if (message.getTimestamp() == null || message.getTimestamp().trim().isEmpty()) {
            return false;
        }

        if (message.getMessageType() == null) {
            return false;
        }

        String type = message.getMessageType().toString().toUpperCase();
        if (!type.equals("TEXT") && !type.equals("JOIN") && !type.equals("LEAVE")) {
            return false;
        }

        return true;
    }

    private void sendAckResponse(WebSocketSession session, ChatMessage chatMessage) {
        try {
            Map<String, Object> ack = new HashMap<>();
            ack.put("status", "SUCCESS");
            ack.put("messageId", java.util.UUID.randomUUID().toString());
            ack.put("timestamp", java.time.Instant.now().toString());
            ack.put("originalMessage", chatMessage);

            String jsonResponse = objectMapper.writeValueAsString(ack);
            boolean sent = writeManager.sendMessage(session, jsonResponse);

            if (sent) {
                acksSent.incrementAndGet();
            } else {
                acksFailed.incrementAndGet();
                log.warn("Failed to queue ACK for session {}", session.getId());
            }

        } catch (Exception e) {
            log.error("Failed to send ACK to session {}: {}", session.getId(), e.getMessage());
            acksFailed.incrementAndGet();
        }
    }

    private void sendErrorResponse(WebSocketSession session, String errorMessage) {
        try {
            ChatMessage errorResponse = new ChatMessage();
            errorResponse.setMessageType(ChatMessage.MessageType.valueOf("ERROR"));
            errorResponse.setMessage(errorMessage);
            errorResponse.setUserId("system");
            errorResponse.setUsername("system");
            errorResponse.setTimestamp(java.time.Instant.now().toString());

            String jsonResponse = objectMapper.writeValueAsString(errorResponse);
            writeManager.sendMessage(session, jsonResponse);

        } catch (Exception e) {
            log.error("Failed to send error response to session {}: {}", session.getId(), e.getMessage());
        }
    }

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

    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getMessagesPublished() {
        return messagesPublished.get();
    }

    public long getMessagesFailed() {
        return messagesFailed.get();
    }

    public long getAcksSent() {
        return acksSent.get();
    }

    public long getAcksFailed() {
        return acksFailed.get();
    }
}