# ChatFlow WebSocket Server

Spring Boot-based WebSocket server for CS6650 Assignment 1.

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- AWS EC2 account (for deployment)

## Project Structure

```
server/
├── src/main/java/com/chatflow/server/
│   ├── ChatFlowServerApplication.java
│   ├── config/
│   │   └── WebSocketConfig.java
│   ├── controller/
│   │   └── HealthController.java
│   ├── handler/
│   │   └── ChatWebSocketHandler.java
│   ├── model/
│   │   ├── ChatMessage.java
│   │   └── MessageResponse.java
│   └── validator/
│       └── MessageValidator.java
├── src/main/resources/
│   └── application.properties
└── pom.xml
```

## Building the Server

```bash
cd server
mvn clean package
```

This creates `target/chatflow-server-1.0.0.jar`

## Running Locally

```bash
java -jar target/chatflow-server-1.0.0.jar
```

Server starts on `http://localhost:8080`

## Testing Locally

### Test Health Endpoint
```bash
curl http://localhost:8080/health
```

### Test WebSocket with wscat

Install wscat:
```bash
npm install -g wscat
```

Connect to WebSocket:
```bash
wscat -c ws://localhost:8080/chat/1
```

Send a test message:
```json
{
  "userId": "12345",
  "username": "testuser",
  "message": "Hello World!",
  "timestamp": "2025-01-15T10:30:00Z",
  "messageType": "TEXT"
}
```

## AWS EC2 Deployment

### 1. Launch EC2 Instance

```bash
# Launch t2.micro instance (free tier)
- AMI: Amazon Linux 2023 or Ubuntu 22.04
- Instance Type: t2.micro
- Region: us-west-2
```

### 2. Configure Security Group

Add inbound rules:
- SSH (Port 22) - Your IP
- Custom TCP (Port 8080) - 0.0.0.0/0
- HTTP (Port 80) - Optional

### 3. Connect to EC2

```bash
ssh -i your-key.pem ec2-user@your-ec2-public-ip
```

### 4. Install Java

```bash
# For Amazon Linux 2023
sudo yum update -y
sudo yum install java-17-amazon-corretto-devel -y

# For Ubuntu
sudo apt update
sudo apt install openjdk-17-jdk -y

# Verify installation
java -version
```

### 5. Upload JAR File

From your local machine:
```bash
scp -i your-key.pem target/chatflow-server-1.0.0.jar ec2-user@your-ec2-ip:~/
```

### 6. Run Server on EC2

```bash
# Simple run (foreground)
java -jar chatflow-server-1.0.0.jar

# Run in background with screen
screen -S chatflow
java -jar chatflow-server-1.0.0.jar
# Press Ctrl+A, then D to detach

# Run with nohup
nohup java -jar chatflow-server-1.0.0.jar > server.log 2>&1 &
```

### 7. Create Systemd Service (Recommended)

Create service file:
```bash
sudo nano /etc/systemd/system/chatflow.service
```

Add content:
```ini
[Unit]
Description=ChatFlow WebSocket Server
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user
ExecStart=/usr/bin/java -jar /home/ec2-user/chatflow-server-1.0.0.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

Enable and start service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable chatflow
sudo systemctl start chatflow
sudo systemctl status chatflow
```

View logs:
```bash
sudo journalctl -u chatflow -f
```

## Configuration

Edit `application.properties` to customize:

```properties
# Change port
server.port=8080

# Adjust thread pool for high load
server.tomcat.threads.max=400
server.tomcat.max-connections=10000

# Logging level
logging.level.com.chatflow.server=DEBUG
```

## API Endpoints

### WebSocket Endpoint
- **URL**: `ws://<host>:8080/chat/{roomId}`
- **Protocol**: WebSocket
- **Message Format**: JSON (see below)

### Health Check
- **URL**: `http://<host>:8080/health`
- **Method**: GET
- **Response**: JSON with server status

## Message Format

### Request (Client → Server)
```json
{
  "userId": "string (1-100000)",
  "username": "string (3-20 alphanumeric)",
  "message": "string (1-500 chars)",
  "timestamp": "ISO-8601 timestamp",
  "messageType": "TEXT|JOIN|LEAVE"
}
```

### Response (Server → Client)

Success:
```json
{
  "status": "SUCCESS",
  "serverTimestamp": "2025-01-15T10:30:01Z",
  "originalMessage": { /* original message */ }
}
```

Error:
```json
{
  "status": "ERROR",
  "serverTimestamp": "2025-01-15T10:30:01Z",
  "errorMessage": "userId must be between 1 and 100000"
}
```

## Monitoring

### Check Server Status
```bash
curl http://your-ec2-ip:8080/health
```

### View Real-time Logs
```bash
sudo journalctl -u chatflow -f
```

### Monitor Resource Usage
```bash
top
htop
```

## Troubleshooting

### Port Already in Use
```bash
# Find process using port 8080
sudo lsof -i :8080
# Kill process
sudo kill -9 <PID>
```

### Connection Refused
- Check security group allows inbound on port 8080
- Verify server is running: `sudo systemctl status chatflow`
- Check firewall: `sudo firewall-cmd --list-all` (RHEL/CentOS)

### Out of Memory
```bash
# Run with increased heap
java -Xmx1024m -jar chatflow-server-1.0.0.jar
```

## Performance Tuning

For high load scenarios:

```bash
# Increase file descriptors
ulimit -n 65536

# Run with optimized JVM settings
java -Xmx1024m -Xms512m \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -jar chatflow-server-1.0.0.jar
```

## Testing with Client

Once server is deployed:
```bash
# From client machine
java -jar chatflow-client-1.0.0.jar ws://your-ec2-ip:8080
```

## Stopping the Server

```bash
# If using systemd
sudo systemctl stop chatflow

# If running with screen
screen -r chatflow
# Press Ctrl+C

# If running with nohup
pkill -f chatflow-server
```
