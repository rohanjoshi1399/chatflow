package com.chatflow.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @JsonProperty("userId")
    private String userId;  // userId

    @JsonProperty("username")
    private String username;  // username

    @JsonProperty("message")
    private String message;  // message

    @JsonProperty("timestamp")
    private String timestamp;  // ISO-8601 format

    @JsonProperty("messageType")
    private MessageType messageType;

    public enum MessageType {
        TEXT, JOIN, LEAVE
    }

    public boolean isQueuedMessage() {
        return messageType == MessageType.TEXT;
    }
    
    public boolean isSynchronousMessage() {
        return messageType == MessageType.JOIN || messageType == MessageType.LEAVE;
    }
}