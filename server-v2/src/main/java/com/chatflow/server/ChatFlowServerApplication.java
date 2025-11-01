package com.chatflow.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
public class ChatFlowServerApplication {

    public static void main(String[] args) {
        log.info("Starting ChatFlow Server...");
        SpringApplication.run(ChatFlowServerApplication.class, args);
        log.info("ChatFlow Server started successfully!");
        log.info("Health check: http://{}:8080/health", args.length > 0 ? args[0] : "localhost");
        log.info("Websocket endpoint: ws://{}:8080/ws", args.length > 0 ? args[0] : "localhost");
    }
}