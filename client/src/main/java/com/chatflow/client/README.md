# ChatFlow Load Test Client

Multi-threaded WebSocket client for load testing the ChatFlow server.

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- Running ChatFlow server (local or EC2)

## Project Structure

```
client/
├── src/main/java/com/chatflow/client/
│   ├── LoadTestClient.java (Main)
│   ├── analyzer/
│   │   └── PerformanceAnalyzer.java
│   ├── generator/
│   │   └── MessageGenerator.java
│   ├── model/
│   │   ├── ChatMessage.java
│   │   └── MessageMetrics.java
│   └── worker/
│       └── WebSocketClientWorker.java
└── pom.xml
```

## Building the Client

```bash
cd client
mvn clean package
```

This creates `target/chatflow-client-1.0.0.jar`

## Running the Client

### Basic Usage

```bash
java -jar target/chatflow-client-1.0.0.jar ws://<server-url>:8080
```

### Examples

Local server:
```bash
java -jar target/chatflow-client-1.0.0.jar ws://localhost:8080
```

EC2 server:
```bash
java -jar target/chatflow-client-1.0.0.jar ws://ec2-xx-xx-xx-xx.us-west-2.compute.amazonaws.com:8080
```

## Test Configuration

The client is configured to run the exact test specified in Assignment 1:

### Phase 1: Warmup
- **Threads**: 32
- **Messages per thread**: 1,000
- **Total messages**: 32,000

### Phase 2: Main Load
- **Threads**: 100 (configurable)
- **Total messages**: 468,000
- **Overall total**: 500,000 messages

### Message Distribution
- **90%** TEXT messages
- **5%** JOIN messages
- **5%** LEAVE messages
- **Room IDs**: Random (1-20)
- **User IDs**: Random (1-100,000)

## Output

### Part 1: Basic Metrics

```
========================================
       PART 1: BASIC METRICS
========================================
Total Messages Sent:     500000
Successful Messages:     498532
Failed Messages:         1468
Total Runtime:           125432 ms (125.43 seconds)
Overall Throughput:      3973.45 messages/second
Success Rate:            99.71%
========================================
```

### Part 2: Performance Statistics

```
========================================
       PERFORMANCE STATISTICS
========================================
Total Messages:     498532
Mean Latency:       45.23 ms
Median Latency:     42.15 ms
95th Percentile:    87.34 ms
99th Percentile:    156.78 ms
Min Latency:        12.45 ms
Max Latency:        892.34 ms
========================================

Message Type Distribution:
  TEXT: 448678 (90.00%)
  JOIN: 24927 (5.00%)
  LEAVE: 24927 (5.00%)

Top 5 Rooms by Throughput:
  Room 15: 25234 messages
  Room 8: 25102 messages
  Room 3: 24987 messages
  Room 12: 24856 messages
  Room 19: 24732 messages
========================================
```

### Part 3: Throughput Visualization

```
========================================
   THROUGHPUT OVER TIME (10s buckets)
========================================
Time (s)  | Messages/Second
----------|----------------
       0  |         3245.50
      10  |         4156.80
      20  |         4289.20
      30  |         4312.10
      ...
========================================
```

## CSV Output

The client generates a CSV file with detailed metrics:

**Filename**: `metrics_<timestamp>.csv`

**Format**:
```csv
timestamp,messageType,latency,statusCode,roomId
1705318200123,TEXT,45,SUCCESS,5
1705318200145,JOIN,52,SUCCESS,12
1705318200167,TEXT,38,SUCCESS,3
...
```

## Tuning Performance

### Adjust Thread Count

Edit `LoadTestClient.java`:
```java
private static final int PHASE2_THREADS = 150; // Increase for higher throughput
```

### Adjust Message Queue Size

```java
private static final int MESSAGE_QUEUE_SIZE = 20000; // Larger buffer
```

### Connection Pooling

The client automatically reuses WebSocket connections per thread for efficiency.

## Little's Law Calculation

Before running, calculate expected throughput:

```
L = λ × W

Where:
L = Number of concurrent threads
λ = Throughput (messages/sec)
W = Average latency (seconds)

Example:
If avg latency = 50ms (0.05s)
With 100 threads:
λ = 100 / 0.05 = 2000 messages/sec
```

### Measuring Single Message Latency

Run a quick test:
```bash
# Send 1000 messages with 1 thread
# Modify TOTAL_MESSAGES and WARMUP_THREADS in code
```

## Monitoring During Test

### Real-time Progress

The client logs progress:
```
INFO  - MessageGenerator started - will generate 500000 messages
INFO  - Generated 50000 messages
INFO  - Generated 100000 messages
...
INFO  - Warmup completed: 8234 ms, 3886.45 messages/second
INFO  - Main phase completed: 117198 ms, 3993.12 messages/second
```

### Check Server Health

While test is running:
```bash
curl http://your-ec2-ip:8080/health
```

## Troubleshooting

### Connection Refused
- Verify server is running
- Check server URL is correct
- Ensure port 8080 is accessible

### Out of Memory
```bash
# Increase heap size
java -Xmx2048m -jar chatflow-client-1.0.0.jar ws://server:8080
```

### Slow Performance
- Reduce `PHASE2_THREADS` if overwhelming server
- Check network latency to server
- Verify server has adequate resources

### Connection Timeouts
- Increase connection timeout in `WebSocketClientWorker`
- Check network stability
- Verify server can handle connection load

## Advanced Configuration

### Running Multiple Clients

To simulate even higher load, run multiple client instances:

```bash
# Terminal 1
java -jar chatflow-client-1.0.0.jar ws://server:8080

# Terminal 2
java -jar chatflow-client-1.0.0.jar ws://server:8080
```

### Distributed Testing

Run clients from multiple machines:
```bash
# Machine 1 (us-west)
java -jar chatflow-client-1.0.0.jar ws://server:8080

# Machine 2 (us-east)
java -jar chatflow-client-1.0.0.jar ws://server:8080
```

## Performance Tips

### JVM Optimization
```bash
java -Xmx2048m -Xms1024m \
     -XX:+UseG1GC \
     -XX:+ParallelRefProcEnabled \
     -jar chatflow-client-1.0.0.jar ws://server:8080
```

### Network Optimization
```bash
# Increase file descriptors (Linux/Mac)
ulimit -n 65536

# Check current limit
ulimit -n
```

## Analyzing Results

### Open CSV in Excel/Python

```python
import pandas as pd
import matplotlib.pyplot as plt

# Load data
df = pd.read_csv('metrics_1705318200.csv')

# Plot latency distribution
plt.hist(df['latency'], bins=50)
plt.xlabel('Latency (ms)')
plt.ylabel('Frequency')
plt.title('Latency Distribution')
plt.show()

# Calculate percentiles
print(f"P50: {df['latency'].quantile(0.50)}")
print(f"P95: {df['latency'].quantile(0.95)}")
print(f"P99: {df['latency'].quantile(0.99)}")
```

## Expected Results

### Good Performance
- Throughput: 3000-5000 msg/sec (single t2.micro)
- P95 Latency: < 100ms
- Success Rate: > 99%

### Excellent Performance
- Throughput: > 5000 msg/sec
- P95 Latency: < 50ms
- Success Rate: > 99.9%

## Bonus Points Strategy

To achieve fastest implementation:

1. **Optimize thread count** based on Little's Law
2. **Tune JVM settings** for GC and memory
3. **Use connection pooling** efficiently
4. **Minimize object creation** in hot paths
5. **Profile with VisualVM** to find bottlenecks
6. **Test on t2.small or t2.medium** for better server performance

## Clean Up

After testing:
```bash
# Stop client (Ctrl+C if running)
# Clean up CSV files
rm metrics_*.csv

# Archive results
mkdir results
mv *.csv results/
```
