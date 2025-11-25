- **Threads:** 64 persistent connections
- **Success Rate:** 99.8%

### Endurance Test (30 minutes)
- **Target:** 4,320,000 messages
- **Actual:** 1,040,000 messages (stalled at 6 minutes)
- **Throughput:** 2,889 msg/sec before stall
- **Bottleneck:** Client message generation capacity

## Test Results Files

- `part2_metrics.csv` - Detailed message-level metrics from Part 2 testing
- `throughput_over_time.csv` - Throughput measurements over time intervals
- Test outputs and screenshots are documented in `assignment3-report.html`

## Running Load Tests

```bash
# Build the client
cd client
mvn clean package -DskipTests

# Run baseline test (500K messages)
java -jar target/chatflow-client-1.0-SNAPSHOT.jar ws://your-alb-endpoint/chat/

# The client will output the Metrics API URL at the end of the test
```

## Key Metrics

All core queries met their performance targets:
- Query 1 (room messages): 42ms < 100ms 
- Query 2 (user history): 118ms < 200ms 
- Query 3 (active users): 287ms < 500ms 
- Query 4 (user rooms): 21ms < 50ms 

Analytics queries with materialized views showed 100-200x improvement over raw queries.
