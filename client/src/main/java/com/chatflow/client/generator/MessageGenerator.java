package com.chatflow.client.generator;

import com.chatflow.client.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.BlockingQueue;

@Slf4j
public class MessageGenerator implements Runnable {
    private final BlockingQueue<ChatMessage> messageQueue;
    private final int totalMessages;
    private final Random random;

    private static final String[] SAMPLE_MESSAGES = {
            "Hello everyone!", "How are you doing?", "Great weather today!",
            "Anyone up for coffee?", "Working on exciting project",
            "Happy Monday!", "TGIF!", "Good morning team",
            "Thanks for the update", "Let's catch up later",
            "Awesome work!", "Congratulations!", "Well done",
            "Meeting at 3pm", "Check your email", "Updated the document",
            "Push to production", "Code review needed", "Bug fixed",
            "New feature deployed", "Server is up", "Database migrated",
            "Performance improved", "Tests passing", "CI/CD working",
            "Sprint planning tomorrow", "Stand-up in 5 mins", "Demo ready",
            "Documentation updated", "Refactored the code", "Optimized query",
            "Fixed the memory leak", "Reduced latency", "Increased throughput",
            "Load balancer configured", "Cache invalidated", "API deployed",
            "Monitoring enabled", "Alerts configured", "Logs analyzed",
            "Security patch applied", "Backup completed", "Rollback successful",
            "Scaling horizontally", "Database indexed", "Query optimized",
            "Frontend updated", "Backend deployed", "Infrastructure ready"
    };

    public MessageGenerator(BlockingQueue<ChatMessage> messageQueue, int totalMessages) {
        this.messageQueue = messageQueue;
        this.totalMessages = totalMessages;
        this.random = new Random();
    }

    @Override
    public void run() {
        log.info("Message Generator: Starting to generate {} messages", totalMessages);

        for (int i = 0; i < totalMessages; i++) {
            try {
                ChatMessage message = generateMessage();
                messageQueue.put(message);

                if ((i + 1) % 10000 == 0) {
                    log.info("Generated {} messages", i + 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Message generation interrupted");
                break;
            }
        }

        log.info("Message Generator: Completed generating all messages");
    }

    private ChatMessage generateMessage() {
        String userId = String.valueOf(random.nextInt(100000) + 1);
        String username = "user" + userId;
        String message = SAMPLE_MESSAGES[random.nextInt(SAMPLE_MESSAGES.length)];
        String timestamp = Instant.now().toString();

        // Message type distribution: 90% TEXT, 5% JOIN, 5% LEAVE
        ChatMessage.MessageType messageType;
        int typeRoll = random.nextInt(100);
        if (typeRoll < 90) {
            messageType = ChatMessage.MessageType.TEXT;
        } else if (typeRoll < 95) {
            messageType = ChatMessage.MessageType.JOIN;
        } else {
            messageType = ChatMessage.MessageType.LEAVE;
        }

        return new ChatMessage(userId, username, message, timestamp, messageType);
    }
}