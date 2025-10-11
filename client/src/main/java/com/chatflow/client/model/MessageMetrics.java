
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
public class MessageMetrics {

    @JsonProperty("sendTime")
    private long sendTime;

    @JsonProperty("receiveTime")
    private long receiveTime;

    @JsonProperty("messageType")
    private String messageType;

    @JsonProperty("statusCode")
    private int statusCode;

    @JsonProperty("roomId")
    private String roomId;

    public long getLatency() {
        return receiveTime - sendTime;
    }
}