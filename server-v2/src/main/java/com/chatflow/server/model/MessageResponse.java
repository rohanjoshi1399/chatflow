package com.chatflow.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response message sent back to client
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {
    
    @JsonProperty("status")
    private String status; // "SUCCESS" or "ERROR"
    
    @JsonProperty("serverTimestamp")
    private String serverTimestamp;
    
    @JsonProperty("originalMessage")
    private ChatMessage originalMessage;
    
    @JsonProperty("errorMessage")
    private String errorMessage;

    // Convenience constructor for success response
    public MessageResponse(String status, String serverTimestamp, ChatMessage originalMessage) {
        this.status = status;
        this.serverTimestamp = serverTimestamp;
        this.originalMessage = originalMessage;
        this.errorMessage = null;
    }

    // Convenience constructor for error response
    public MessageResponse(String status, String errorMessage) {
        this.status = status;
        this.serverTimestamp = null;
        this.originalMessage = null;
        this.errorMessage = errorMessage;
    }
}