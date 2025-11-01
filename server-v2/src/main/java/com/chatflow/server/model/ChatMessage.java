package com.chatflow.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
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
}