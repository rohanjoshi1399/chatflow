# AWS Application Load Balancer Setup Guide

## Prerequisites

- 2-4 EC2 instances running the ChatFlow server in the same VPC and security groups

## Part 1: Create Application Load Balancer

### Step 1: Create Target Group

1. Go to **EC2 Console** → **Target Groups** → **Create target group**

2. **Choose target type:**
   - Select: `Instances`
   - Click `Next`

3. **Specify group details:**
   - Target group name: `chatflow-targets`
   - Protocol: `HTTP`
   - Port: `8080`
   - VPC: Select your VPC
   - Protocol version: `HTTP1`

4. **Health check settings:**
   ```
   Health check protocol: HTTP
   Health check path: /health
   Port: Traffic port
   
   Advanced health check settings:
   - Healthy threshold: 2
   - Unhealthy threshold: 3
   - Timeout: 5 seconds
   - Interval: 30 seconds
   - Success codes: 200
   ```

5. **Tags (optional):**
   - Key: `Name`
   - Value: `chatflow-target-group`

6. Click **Next**

### Step 2: Register Targets

1. **Register targets:**
   - Select all your EC2 instances
   - Ports for selected instances: `8080`
   - Click `Include as pending below`

2. **Review and create:**
   - Click `Create target group`

3. **Verify health checks:**
   - Go to target group → **Targets** tab
   - Wait 1-2 minutes for instances to become `healthy`

### Step 3: Create Application Load Balancer

1. Go to **EC2 Console** → **Load Balancers** → **Create Load Balancer**

2. **Select load balancer type:**
   - Choose `Application Load Balancer`

3. **Basic configuration:**
   ```
   Load balancer name: chatflow-alb
   Scheme: Internet-facing
   IP address type: IPv4
   ```

4. **Network mapping:**
   - VPC: Select your VPC
   - Mappings: Select at least 2 availability zones
   - Select public subnets for each AZ

5. **Security groups:**
   - Create new security group or select existing:
     ```
     Inbound rules:
     - Type: HTTP, Port: 80, Source: 0.0.0.0/0
     - Type: Custom TCP, Port: 8080, Source: 0.0.0.0/0
     ```

6. **Listeners and routing:**
   ```
   Listener: HTTP:80
   Default action: Forward to chatflow-targets
   ```
   
   Add another listener:
   ```
   Listener: HTTP:8080
   Default action: Forward to chatflow-targets
   ```

7. **Load balancer attributes:**
   - Click on load balancer → **Attributes** tab → **Edit**
   - Enable: `Idle timeout` → Set to `120 seconds` (for WebSocket)

8. Click **Create load balancer**

### Step 4: Configure Sticky Sessions (REQUIRED for WebSocket)

1. Go to your target group: `chatflow-targets`

2. Click **Attributes** tab → **Edit**

3. **Stickiness:**
   ```
   Stickiness type: Load balancer generated cookie
   Stickiness duration: 86400 seconds (1 day)
   Cookie name: AWSALB
   ```

4. Click **Save changes**

### Step 5: Update Security Groups

1. **ALB Security Group:**
   ```
   Inbound:
   - HTTP (80) from 0.0.0.0/0
   - HTTP (8080) from 0.0.0.0/0
   
   Outbound:
   - All traffic to 0.0.0.0/0
   ```

2. **EC2 Instance Security Group:**
   ```
   Inbound:
   - Custom TCP (8080) from ALB security group
   - SSH (22) from your IP
   
   Outbound:
   - All traffic to 0.0.0.0/0
   ```

## Part 2: Test ALB Configuration

### Step 1: Get ALB DNS Name

```bash
# In AWS Console, get the ALB DNS name
# Format: chatflow-alb-XXXXXXXXX.us-west-2.elb.amazonaws.com
```

### Step 2: Test Health Endpoint

```bash
curl http://chatflow-alb-XXXXXXXXX.us-west-2.elb.amazonaws.com/health

# This should return a response from one of the servers
```

### Step 3: Test WebSocket Connection

We can now test the WebSocket connection by updating the client to use the ALB URL:

```bash
java -cp target/websocket-client-1.0.0-jar-with-dependencies.jar \
  com.chatflow.client.LoadTestClient \
  ws://chatflow-alb-XXXXXXXXX.us-west-2.elb.amazonaws.com:8080/chat/
```

### Step 4: Verify Load Distribution

1. **Check target group health:**
   - EC2 Console → Target Groups → chatflow-targets
   - All targets should show `healthy`

2. **Monitor metrics on each instance:**
   ```bash
   # On instance 1
   curl http://localhost:8080/metrics
   
   # On instance 2
   curl http://localhost:8080/metrics
   
   # Compare messagesReceived count - should be distributed
   ```

3. **Check ALB metrics:**
   - EC2 Console → Load Balancers → chatflow-alb
   - **Monitoring** tab
   - Look for:
     - Target connection count
     - Request count per target
     - Healthy/unhealthy target count