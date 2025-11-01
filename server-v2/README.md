# ChatFlow v2 - Distributed Chat System with SQS

**CS6650 Assignment 2**  

## Project Overview

This is an upgraded distributed chat infrastructure that uses AWS SQS for message distribution and supports load balancing across multiple server instances. Instead of simple echo, messages are now broadcast to all users in the same chat room through a message queue system.

### Key Features

- **Message Queue Integration**: AWS SQS FIFO queues for reliable message delivery
- **Multi-Instance Support**: Run on 2-4 load-balanced servers
- **Room-Based Broadcasting**: 20 chat rooms with isolated message distribution
- **Consumer Service**: Multi-threaded message consumers on each server
- **Load Balancing**: AWS Application Load Balancer with sticky sessions
- **Monitoring**: Comprehensive metrics and health checks

## Architecture

```
Client → ALB → Server 1 → SQS Queue (Room 1-20) ⟲ Consumer → WebSocket Broadcast
              Server 2 ↗                        ↘ Consumer → WebSocket Broadcast
              Server 3 ↗                        ↘ Consumer → WebSocket Broadcast
              Server 4 ↗
```

### Message Flow

1. Client sends message via WebSocket to ALB
2. ALB routes to one of the server instances (sticky session)
3. Server validates message and publishes to appropriate SQS queue
4. Consumer threads on ALL servers poll the queue
5. Consumers broadcast message to all connected clients in that room

### Key Components

**Server (`/server-v2`):**
- WebSocket handler (receives messages)
- SQS producer (publishes to queues)
- SQS consumer (polls queues)
- Room manager (tracks sessions and broadcasts)

**Queues (AWS SQS):**
- 20 FIFO queues (one per room)
- Ensures message ordering
- At-least-once delivery guarantee

**Load Balancer (AWS ALB):**
- Distributes connections across servers
- Sticky sessions for WebSocket
- Health checks

## Project Structure

```
chatflow-v2/
├── server-v2/
│   ├── src/main/java/com/chatflow/
│   │   ├── config/
│   │   │   ├── WebSocketConfig.java
│   │   │   └── SQSConfig.java
│   │   ├── handler/
│   │   │   └── ChatWebSocketHandler.java
│   │   ├── service/
│   │   │   ├── SQSService.java
│   │   │   ├── SQSConsumerService.java
│   │   │   └── RoomManager.java
│   │   ├── controller/
│   │   │   └── HealthController.java
│   │   ├── model/
│   │   │   ├── ChatMessage.java
│   │   │   └── QueueMessage.java
│   │   └── ChatFlowApplication.java
│   ├── src/main/resources/
│   │   └── application.properties
│   └── pom.xml
├── client/ (reuse from Assignment 1)
│   ├── src/main/java/com/chatflow/client/
│   │   ├── LoadTestClient.java
│   │   └── LoadTestClientPart2.java
│   └── pom.xml
├── deployment/
│   ├── deploy.sh
│   ├── monitor-queues.sh
│   ├── ALB_SETUP.md
│   └── SQS_SETUP.md
└── README.md (this file)
```

## Setup Instructions

### Prerequisites

- AWS Account with access to EC2, SQS, and ALB
- Java 11 installed
- Maven 3.6+
- AWS CLI configured

### Step 1: Build the Application

```bash
cd server-v2
mvn clean package

# JAR file created: target/chatflow-server-v2-2.0.0.jar
```

### Step 2: Set Up AWS SQS

Follow [deployment/SQS_SETUP.md](./deployment/SQS_SETUP.md) for detailed instructions.

**Quick setup:**

1. Create IAM role with SQS permissions
2. Attach role to EC2 instances
3. Application will auto-create 20 FIFO queues on startup

### Step 3: Deploy to EC2 Instances

Deploy to 2-4 EC2 instances (t2.medium or t3.medium recommended):

```bash
# Deploy to instance 1
./deployment/deploy.sh server-1 <instance-1-ip>

# Deploy to instance 2
./deployment/deploy.sh server-2 <instance-2-ip>

# Deploy to instance 3 (optional)
./deployment/deploy.sh server-3 <instance-3-ip>

# Deploy to instance 4 (optional)
./deployment/deploy.sh server-4 <instance-4-ip>
```

### Step 4: Set Up Application Load Balancer

Follow [deployment/ALB_SETUP.md](./deployment/ALB_SETUP.md) for complete ALB configuration.

**Quick steps:**
1. Create target group with health check on `/health`
2. Register all EC2 instances
3. Create ALB with HTTP:8080 listener
4. Enable sticky sessions (REQUIRED for WebSocket)
5. Update security groups

### Step 5: Verify Deployment

```bash
# Test ALB health
curl http://<alb-dns-name>/health

# Check metrics
curl http://<alb-dns-name>/metrics

# Monitor queues
./deployment/monitor-queues.sh
```

## Running Load Tests

### Test 1: Single Instance Baseline

Test against one instance directly (bypass ALB):

```bash
cd client
mvn clean package

java -cp target/websocket-client-1.0.0-jar-with-dependencies.jar \
  com.chatflow.client.LoadTestClientPart2 \
  ws://<instance-ip>:8080/chat/
```

### Test 2: Load Balanced (Multiple Instances)

```bash
java -cp target/websocket-client-1.0.0-jar-with-dependencies.jar \
  com.chatflow.client.LoadTestClientPart2 \
  ws://<alb-dns-name>:8080/chat/
```

### Test 4: Stress Test (1M messages)

Modify client to send 1 million messages:

```java
// In LoadTestClientPart2.java
private static final int WARMUP_MESSAGES = 32000;
private static final int MAIN_MESSAGES = 1000000;  // Changed from 468000
```

Rebuild and run:

```bash
mvn clean package
java -cp target/websocket-client-1.0.0-jar-with-dependencies.jar \
  com.chatflow.client.LoadTestClientPart2 \
  ws://<alb-dns-name>:8080/chat/
```

## Monitoring

### Real-Time Queue Monitoring

```bash
./deployment/monitor-queues.sh
```

Shows live queue depths for all 20 rooms.

### Application Metrics

```bash
# Overall metrics
curl http://<alb-dns-name>/metrics | jq .

# Specific room queue depth
curl http://<alb-dns-name>/queue/1 | jq .

# All queue depths
curl http://<alb-dns-name>/queue/all | jq .
```

## Configuration

### Server Configuration

```properties
# Server identity (change for each instance)
server.id=server-1

# AWS region
aws.region=us-west-2

# SQS queue configuration
sqs.queue.prefix=chatflow-room-
sqs.fifo.enabled=true

# Consumer settings
sqs.consumer.enabled=true
sqs.consumer.threads=40
sqs.consumer.max.messages=10
sqs.consumer.wait.time=20
sqs.consumer.visibility.timeout=30
```

### Environment Variables (Alternative)

```bash
export SERVER_ID=server-1
export AWS_REGION=us-west-2
export SQS_CONSUMER_THREADS=40
```