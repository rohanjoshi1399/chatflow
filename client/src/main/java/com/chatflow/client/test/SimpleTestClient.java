package com.chatflow.client.test;

import com.chatflow.client.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;

public class SimpleTestClient {
    public static void main(String[] args) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ObjectMapper mapper = new ObjectMapper();

        WebSocketClient client = new WebSocketClient(new URI("ws://34.207.119.4:8080/chat/1")) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                System.out.println("Connected!");

                try {
                    // Create and send a test message
                    ChatMessage msg = new ChatMessage(
                            "12345",
                            "user12345",
                            "Hello World",
                            Instant.now().toString(),
                            ChatMessage.MessageType.TEXT
                    );

                    String json = mapper.writeValueAsString(msg);
                    System.out.println("Sending: " + json);
                    send(json);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onMessage(String message) {
                System.out.println("Received: " + message);
                latch.countDown();
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("Closed: " + reason);
            }

            @Override
            public void onError(Exception ex) {
                System.err.println("Error: " + ex.getMessage());
                ex.printStackTrace();
            }
        };

        client.connectBlocking();
        latch.await();
        client.close();

        System.out.println("Test complete!");
    }
}