# JMeter Testing Instructions for Assignment 4

## Overview

This folder contains JMeter test plans for performance testing your chat application.

## Test Plans

1. **baseline-performance-test.jmx** - 1000 users, 100K calls, 5 minutes
2. **stress-test.jmx** - 500 users, 200K-500K calls, 30 minutes (coming soon)

## What the Test Actually Does

### 70% READ Operations = Database Query Performance Testing

The read thread group (700 threads) repeatedly calls your **database query APIs** to test read performance:

- **`GET /api/metrics/core-queries`** - Runs 4 core database queries:
  1. Get messages for a specific room (with time window)
  2. Get user message history
  3. Count active users in time period
  4. Get rooms a user has participated in

- **`GET /api/metrics/all`** - Comprehensive query that runs:
  - All 4 core queries above
  - Analytics from materialized views (top users, top rooms, participation patterns)
  - Database statistics

**This tests:** Database read performance, index effectiveness, query optimization, materialized view performance, connection pooling for SELECT queries.

### 30% WRITE Operations = Message Persistence Testing

The write thread group (300 threads) sends messages via WebSocket:

- Connect to `/chat/{roomId}` (random room 1-20)
- Send chat message via WebSocket
- Message goes to SQS → Consumer → **Database INSERT**
- Close WebSocket connection

**This tests:** WebSocket connection handling, SQS queue throughput, database write performance, batch insert optimization, connection pooling for INSERT operations.

---

## Prerequisites

### 1. Create users.csv

Before running tests, you need a `users.csv` file with test user data.

**Create users.csv:**
```csv
user1,User 1
user2,User 2
user3,User 3
...
user1000,User 1000
```

Quick Python script to generate it:
```python
with open('users.csv', 'w') as f:
    for i in range(1, 1001):
        f.write(f'user{i},User {i}\n')
```

Or use this one-liner (Windows PowerShell):
```powershell
1..1000 | ForEach-Object { "user$_,User $_" } | Out-File -Encoding utf8 users.csv
```

Mac/Linux:
```bash
for i in {1..1000}; do echo "user$i,User $i"; done > users.csv
```

## Running the Baseline Test

### Step 1: Start Your Server

**IMPORTANT:** Before running the baseline test, you should have some data in your database! The read operations query message history, so if your database is empty, all the read queries will return empty results.

**Option 1: Run a quick data seeding script first**
**Option 2: Run write operations first (disable read thread group temporarily)**

```bash
cd server-v2
mvn clean package
java -jar target/chatflow-server-1.0-SNAPSHOT.jar
```

Verify it's running:
```bash
curl http://localhost:8080/health
```

### Step 2: Seed Some Test Data (Recommended)

To get meaningful read performance metrics, populate your database with some messages first. You can do this by:

1. **Temporarily disable the read thread group** in JMeter
2. **Run just the write operations** for a few minutes to generate data
3. **Re-enable read operations** and run the full test

Or run a separate client to seed data before the baseline test.

### Step 3: Run the Test (Non-GUI Mode)

```bash
cd jmeter

# Run with default settings (1000 users, 5 min)
jmeter -n -t baseline-performance-test.jmx -l baseline-results.jtl -e -o baseline-report/

# Run with custom parameters
jmeter -n -t baseline-performance-test.jmx -l baseline-results.jtl -Jusers=500 -e -o baseline-report/
```

### Step 4: View Results

Open `baseline-report/index.html` in your browser to see:
- Response time charts
- Throughput graphs  
- Error rates
- Percentiles (p50, p90, p95, p99)
- All the metrics you need for Assignment 4!

## Test Parameters

You can override these from command line:

| Parameter | Default | Override Example |
|-----------|---------|------------------|
| SERVER_HOST | localhost | `-JserverHost=myserver.com` |
| SERVER_PORT | 8080 | `-JserverPort=9090` |
| NUM_USERS | 1000 | `-Jusers=500` |
| TEST_DURATION | 300 (5 min) | `-Jduration=600` (10 min) |

## Troubleshooting

**Server connection refused:**
- Make sure your server is running
- Check `http://localhost:8080/health` works

**WebSocket errors:**
- Ensure WebSocket plugin is installed in JMeter
- Check WebSocket path is correct: `/chat/{roomId}`

**Out of memory:**
- Increase JMeter heap: Edit `jmeter.bat`, change to `-Xms2g -Xmx4g`
- Use non-GUI mode (GUI uses much more memory)

**Too slow/taking forever:**
- Reduce number of users: `-Jusers=100`
- Reduce duration: `-Jduration=60`
- Check server isn't overloaded

## Collecting AWS Metrics

While JMeter is running, collect these from AWS CloudWatch:

**EC2 Metrics:**
- CPU Utilization
- Network In/Out
- Status Checks

**Aurora/RDS Metrics:**
- Database Connections
- CPU Utilization  
- Write IOPS
- Write Latency

**ALB Metrics:**
- Request Count
- Target Response Time
- Healthy Host Count

You'll need these for your PDF report!

## Expected Results

For a well-optimized system, you should see:

**Response Times:**
- Average: < 100ms
- p95: < 200ms
- p99: < 500ms

**Throughput:**
- 1000-3000 requests/second

**Error Rate:**
- < 1%

If your numbers are significantly different, check:
1. Server configuration
2. Database indexes
3. Connection pools
4. Resource limits (file descriptors, memory)

## Next Steps

1. Run baseline test on CURRENT system
2. Document the results
3. Wait for teammates to implement optimizations
4. Run the SAME test after optimizations
5. Compare the results
6. Create charts showing improvement %
7. Add to PDF report

Good luck! 🚀
