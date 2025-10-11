package com.chatflow.server.handler;

import com.chatflow.server.model.ChatMessage;
import com.chatflow.server.model.MessageResponse;
import com.chatflow.server.validator.MessageValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final boolean VERBOSE_LOGGING = false; // Set to true for detailed logs

    private final MessageValidator validator;
    private final ObjectMapper objectMapper;
    private final Map<String, WebSocketSession> sessions;
    private final Map<String, String> sessionToRoom;
    private final AtomicInteger messageCounter;
    private final AtomicInteger successCounter;
    private final AtomicInteger errorCounter;

    public ChatWebSocketHandler(MessageValidator validator) {
        this.validator = validator;
        this.objectMapper = new ObjectMapper();
        this.sessions = new ConcurrentHashMap<>();
        this.sessionToRoom = new ConcurrentHashMap<>();
        this.messageCounter = new AtomicInteger(0);
        this.successCounter = new AtomicInteger(0);
        this.errorCounter = new AtomicInteger(0);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        String roomId = extractRoomId(session);
        sessionToRoom.put(session.getId(), roomId);

        // Only log connections, not every message
        log.info("Connection established | Session: {} | Room: {}",
                session.getId(), roomId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        String roomId = sessionToRoom.get(session.getId());
        int msgNum = messageCounter.incrementAndGet();

        try {
            ChatMessage chatMessage = objectMapper.readValue(payload, ChatMessage.class);

            // Only log every 10000th message OR errors
            if (VERBOSE_LOGGING || msgNum % 10000 == 0) {
                log.info("Message #{} | Room: {} | User: {} | Type: {}", msgNum, roomId, chatMessage.getUserId(), chatMessage.getMessageType());
            }

            String validationError = validator.validate(chatMessage);

            MessageResponse response;
            if (validationError != null) {
                errorCounter.incrementAndGet();
                // Always log errors
                log.warn("Message #{} REJECTED | Room: {} | Reason: {}",
                        msgNum, roomId, validationError);
                response = new MessageResponse("ERROR", validationError);
            } else {
                successCounter.incrementAndGet();
                String serverTimestamp = Instant.now().toString();
                response = new MessageResponse("SUCCESS", serverTimestamp, chatMessage);
            }

            String jsonResponse = objectMapper.writeValueAsString(response);
            session.sendMessage(new TextMessage(jsonResponse));

        } catch (Exception e) {
            errorCounter.incrementAndGet();
            // Always log parsing errors
            log.error("Message #{} PARSING ERROR | Room: {} | Error: {}",
                    msgNum, roomId, e.getMessage());

            MessageResponse errorResponse = new MessageResponse("ERROR",
                    "Invalid JSON format: " + e.getMessage());
            String jsonResponse = objectMapper.writeValueAsString(errorResponse);
            session.sendMessage(new TextMessage(jsonResponse));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String roomId = sessionToRoom.remove(session.getId());
        sessions.remove(session.getId());

        log.info("Connection closed | Session: {} | Room: {} | Total processed: {} | Success: {} | Errors: {}",
                session.getId(), roomId, messageCounter.get(), successCounter.get(), errorCounter.get());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String roomId = sessionToRoom.get(session.getId());

        log.error("Transport error | Session: {} | Room: {} | Error: {}",
                session.getId(), roomId, exception.getMessage());

        sessions.remove(session.getId());
        sessionToRoom.remove(session.getId());
    }

    private String extractRoomId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] parts = path.split("/");
        return parts[parts.length - 1];
    }

    public int getActiveConnections() {
        return sessions.size();
    }

    public int getTotalMessagesProcessed() {
        return messageCounter.get();
    }

    public int getSuccessCount() {
        return successCounter.get();
    }

    public int getErrorCount() {
        return errorCounter.get();
    }
}