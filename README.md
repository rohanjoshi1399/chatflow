# ChatFlow - Scalable Distributed Chat System

**ChatFlow** is a high-performance, distributed chat system designed to handle high concurrency and massive message throughput. This project demonstrates a scalable architecture using WebSocket for real-time communication, AWS SQS for message distribution, and a partitioned database for persistence. Built as a part of the course CS6650 - Building Scalable Distributed Systems

## 🏗️ Architecture Overview

The system evolved through multiple iterations to achieve high scalability and performance:

**Key Components:**
*   **Load Balancer (AWS ALB)**: Distributes WebSocket connections across multiple server instances using sticky sessions.
*   **Server Nodes (4x EC2)**: Stateless Java servers that handle WebSocket connections and publish messages to SQS queues.
*   **Message Broker (AWS SQS)**: 20 FIFO queues (one per chat room) ensure ordered delivery of messages.
*   **Consumer Service**: Multi-threaded consumers on each server poll SQS queues and batch messages for persistence.
*   **Batch Writer**: Optimized batch insertion service that writes messages to the database in batches of 1,000.
*   **Database (Aurora PostgreSQL)**: Stores chat history in `messages` and `user_activity` tables with materialized views for analytics.

```mermaid
graph TB
    Client[Clients] --> ALB[Load Balancer]
    
    ALB --> Servers[4x Server Nodes<br/>EC2 Instances]
    
    Servers -->|Publish| SQS[SQS FIFO Queues<br/>20 Rooms]
    SQS -->|Consume| Servers
    
    Servers -->|Batch Write<br/>1000 msgs| DB[(Aurora PostgreSQL<br/>messages & user_activity)]
    
    Servers -->|Broadcast| Client
```

**Message Flow:**
1. Client sends message via WebSocket to ALB
2. ALB routes to one server instance (sticky session)
3. Server validates and publishes to the appropriate SQS FIFO queue (based on room ID)
4. Consumer threads on ALL servers poll queues
5. Consumers add messages to batch writer buffer
6. Batch writer flushes 1,000 messages at a time to Aurora PostgreSQL
7. Consumers broadcast message to all connected clients in that room

## 📂 Project Structure

This repository contains several modules that make up the complete ecosystem:

### Core Modules
*   **`server-v2/`** 🌟
    The current, distributed version of the chat server. It implements the "fan-out" architecture using AWS SQS. This is the main application code.
*   **`client/`**
    A high-throughput WebSocket client used for load testing. It simulates thousands of concurrent users to stress-test the system.
*   **`database/`**
    Contains SQL schemas, setup scripts, and materialized view definitions for the PostgreSQL database backend.

### Infrastructure & Tools
*   **`deployment/`**
    Shell scripts and guides for deploying to AWS EC2, configuring the Application Load Balancer (ALB), and setting up SQS queues.
*   **`monitoring/`**
    Scripts to verify system health, monitor SQS queue depths in real-time, and track database performance metrics.
*   **`jmeter/`**
    Apache JMeter test plans for validating performance baselines and conducting heavy load tests.

### Legacy
*   **`server/`**
    The initial v1 implementation. Kept for reference to demonstrate the evolution from a monolithic to a distributed design.

## 🚀 Quick Start

### Prerequisites
*   Java 11+
*   Maven 3.6+
*   (Optional) AWS CLI for deployment

### Building the Project
Both the client and server modules are built using Maven.

```bash
# Build the main server
cd server-v2
mvn clean package

# Build the load test client
cd ../client
mvn clean package
```

### Running Locally (Single Node)
You can run a single instance of the v2 server locally for testing:

```bash
java -jar server-v2/target/chatflow-server-v2-2.0.0.jar
```
*The server will start on port 8080.*

For full deployment instructions, including how to set up the AWS infrastructure, please refer to the detailed guide in [server-v2/README.md](server-v2/README.md).