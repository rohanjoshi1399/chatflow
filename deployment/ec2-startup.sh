#!/bin/bash
# ChatFlow Server Setup - Manual JAR deployment

# Update system
yum update -y

# Install Java 17
yum install -y java-17-amazon-corretto-devel

# Create application directory
mkdir -p /opt/chatflow
mkdir -p /var/log/chatflow

# Set permissions for ec2-user
chown -R ec2-user:ec2-user /opt/chatflow
chown -R ec2-user:ec2-user /var/log/chatflow

# Create systemd service
cat > /etc/systemd/system/chatflow.service << 'EOF'
[Unit]
Description=ChatFlow WebSocket Server
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/opt/chatflow
ExecStart=/usr/bin/java -Xmx1g -jar /opt/chatflow/websocket-server-1.0.0.jar
Restart=always
RestartSec=10
StandardOutput=append:/var/log/chatflow/server.log
StandardError=append:/var/log/chatflow/server.log

[Install]
WantedBy=multi-user.target
EOF

# Reload systemd
systemctl daemon-reload

# Create a helper script for starting the service
cat > /home/ec2-user/start-chatflow.sh << 'EOF'
#!/bin/bash
# Helper script to start ChatFlow service

if [ ! -f /opt/chatflow/websocket-server-1.0.0.jar ]; then
    echo "ERROR: JAR file not found at /opt/chatflow/websocket-server-1.0.0.jar"
    echo "Please upload your JAR file first using:"
    echo "  pscp -i your-key.ppk server/target/websocket-server-1.0.0.jar ec2-user@YOUR_EC2_IP:/opt/chatflow/"
    exit 1
fi

echo "Starting ChatFlow service..."
sudo systemctl enable chatflow
sudo systemctl start chatflow
sleep 5
sudo systemctl status chatflow

# Get the instance's public IP
PUBLIC_IP=$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4)

echo ""
echo "✅ Service started successfully!"
echo ""
echo "📊 Check logs:"
echo "  sudo tail -f /var/log/chatflow/server.log"
echo ""
echo "🔍 Test from this EC2 instance:"
echo "  curl http://localhost:8080/health"
echo ""
echo "🌐 Test from your LOCAL machine:"
echo "  curl http://${PUBLIC_IP}:8080/health"
echo ""
echo "🔌 WebSocket connection URL:"
echo "  ws://${PUBLIC_IP}:8080/chat"
echo ""
echo "⚠️  Make sure Security Group allows inbound traffic on port 8080!"
EOF

chmod +x /home/ec2-user/start-chatflow.sh
chown ec2-user:ec2-user /home/ec2-user/start-chatflow.sh

echo "Setup complete! Upload your JAR and run ~/start-chatflow.sh" > /home/ec2-user/README.txt