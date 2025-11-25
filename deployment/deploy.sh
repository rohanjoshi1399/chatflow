#!/bin/bash

# ChatFlow Server Deployment Script
# Usage: ./deploy.sh <server-id> <instance-ip>
# Example: ./deploy.sh server-1 3.88.45.123

set -e

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <server-id> <instance-ip>"
    echo "Example: $0 server-1 3.88.45.123"
    exit 1
fi

SERVER_ID=$1
INSTANCE_IP=$2
KEY_FILE="your-key.pem"  # Update this with your key file
USER="ec2-user"  # or ubuntu

echo "======================================"
echo "Deploying ChatFlow Server"
echo "Server ID: $SERVER_ID"
echo "Instance IP: $INSTANCE_IP"
echo "======================================"

# Build the application
echo "Building application..."
mvn clean package -DskipTests

if [ ! -f "target/chatflow-server-v2-2.0.0.jar" ]; then
    echo "Error: JAR file not found!"
    exit 1
fi

echo "JAR file built successfully"

# Create application.properties for this instance
echo "Creating application.properties..."
cat > target/application.properties <<EOF
server.port=8080
server.id=$SERVER_ID

aws.region=us-west-2

sqs.queue.prefix=chatflow-room-
sqs.fifo.enabled=true

sqs.consumer.enabled=true
sqs.consumer.threads=40
sqs.consumer.max.messages=10
sqs.consumer.wait.time=20
sqs.consumer.visibility.timeout=30

logging.level.com.chatflow=INFO
logging.level.software.amazon.awssdk=WARN
EOF

echo "Copying files to instance..."
scp -i $KEY_FILE target/chatflow-server-v2-2.0.0.jar $USER@$INSTANCE_IP:/tmp/
scp -i $KEY_FILE target/application.properties $USER@$INSTANCE_IP:/tmp/

echo "Setting up application on instance..."
ssh -i $KEY_FILE $USER@$INSTANCE_IP << 'ENDSSH'
# Install Java if not present
if ! command -v java &> /dev/null; then
    echo "Installing Java..."
    sudo yum install -y java-11-amazon-corretto || sudo apt install -y openjdk-11-jdk
fi

# Create application directory
sudo mkdir -p /opt/chatflow
sudo mv /tmp/chatflow-server-v2-2.0.0.jar /opt/chatflow/
sudo mv /tmp/application.properties /opt/chatflow/

# Create systemd service
sudo tee /etc/systemd/system/chatflow.service > /dev/null <<'EOF'
[Unit]
Description=ChatFlow WebSocket Server
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/opt/chatflow
ExecStart=/usr/bin/java -Xmx1g -Xms512m -jar /opt/chatflow/chatflow-server-v2-2.0.0.jar --spring.config.location=/opt/chatflow/application.properties
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Reload systemd and start service
sudo systemctl daemon-reload
sudo systemctl enable chatflow
sudo systemctl stop chatflow || true
sleep 2
sudo systemctl start chatflow

# Wait for service to start
sleep 5

# Check service status
sudo systemctl status chatflow --no-pager

echo "Service started successfully!"
ENDSSH

echo ""
echo "======================================"
echo "Deployment completed!"
echo "======================================"
echo ""
echo "Check service status:"
echo "  ssh -i $KEY_FILE $USER@$INSTANCE_IP 'sudo systemctl status chatflow'"
echo ""
echo "View logs:"
echo "  ssh -i $KEY_FILE $USER@$INSTANCE_IP 'sudo journalctl -u chatflow -f'"
echo ""
echo "Test health endpoint:"
echo "  curl http://$INSTANCE_IP:8080/health"
echo ""
echo "Test metrics endpoint:"
echo "  curl http://$INSTANCE_IP:8080/metrics"
echo ""