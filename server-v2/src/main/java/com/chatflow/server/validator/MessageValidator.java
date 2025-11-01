package com.chatflow.server.validator;

import com.chatflow.server.model.ChatMessage;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Slf4j
@Component
public class MessageValidator {

    private static final int MIN_USER_ID = 1;
    private static final int MAX_USER_ID = 100000;
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final int MAX_USERNAME_LENGTH = 20;
    private static final int MIN_MESSAGE_LENGTH = 1;
    private static final int MAX_MESSAGE_LENGTH = 500;

    public String validate(ChatMessage message) {
        if (message == null) {
            return "Message cannot be null";
        }

        // Validate userId
        if (message.getUserId() == null || message.getUserId().trim().isEmpty()) {
            return "userId is required";
        }

        try {
            int userId = Integer.parseInt(message.getUserId());
            if (userId < MIN_USER_ID || userId > MAX_USER_ID) {
                return "userId must be between " + MIN_USER_ID + " and " + MAX_USER_ID;
            }
        } catch (NumberFormatException e) {
            return "userId must be a valid number";
        }

        // Validate username
        if (message.getUsername() == null || message.getUsername().trim().isEmpty()) {
            return "username is required";
        }

        String username = message.getUsername();
        if (username.length() < MIN_USERNAME_LENGTH || username.length() > MAX_USERNAME_LENGTH) {
            return "username must be " + MIN_USERNAME_LENGTH + "-" + MAX_USERNAME_LENGTH + " characters";
        }

        if (!username.matches("^[a-zA-Z0-9]+$")) {
            return "username must be alphanumeric";
        }

        // Validate message
        if (message.getMessage() == null) {
            return "message is required";
        }

        int msgLength = message.getMessage().length();
        if (msgLength < MIN_MESSAGE_LENGTH || msgLength > MAX_MESSAGE_LENGTH) {
            return "message must be " + MIN_MESSAGE_LENGTH + "-" + MAX_MESSAGE_LENGTH + " characters";
        }

        // Validate timestamp
        if (message.getTimestamp() == null || message.getTimestamp().trim().isEmpty()) {
            return "timestamp is required";
        }

        try {
            Instant.parse(message.getTimestamp());
        } catch (DateTimeParseException e) {
            return "timestamp must be valid ISO-8601 format";
        }

        // Validate messageType
        if (message.getMessageType() == null) {
            return "messageType is required (TEXT, JOIN, or LEAVE)";
        }

        return null; // Valid
    }
}