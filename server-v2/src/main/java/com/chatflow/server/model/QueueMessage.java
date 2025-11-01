package com.chatflow.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueMessage {

    @JsonProperty("messageId")
    private String messageId;  // messageId

    @JsonProperty("roomId")
    private String roomId;  // roomId
    
    @JsonProperty("userId")
    private String userId;  // userId

    @JsonProperty("username")
    private String username;  // username

    @JsonProperty("message")
    private String message;  // message

    @JsonProperty("timestamp")
    private String timestamp;  // ISO-8601 format

    @JsonProperty("messageType")
    private ChatMessage.MessageType messageType;

    @JsonProperty("serverId")
    private String serverId;  // serverId

    @JsonProperty("clientIp")
    private String clientIp;  // clientIp

    public QueueMessage(String roomId, String userId, String username,
                        String message, ChatMessage.MessageType messageType, String serverId, String clientIp) {
        this.messageId = UUID.randomUUID().toString();
        this.roomId = roomId;
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = Instant.now().toString();
        this.messageType = messageType;
        this.serverId = serverId;
        this.clientIp = clientIp;
    }
}