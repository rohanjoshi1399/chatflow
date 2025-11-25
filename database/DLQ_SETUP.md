# Dead Letter Queue Setup Guide

## Overview
The Dead Letter Queue (DLQ) captures messages that fail to persist to the database, preventing data loss.

## Architecture

```
SQS Consumer → BatchMessageWriter → Database
                      ↓ (on failure)
                  DLQ Service → SQS DLQ
```

## AWS Setup

### 1. Create DLQ via AWS CLI

```bash
# Create FIFO DLQ for database write failures
aws sqs create-queue \
  --queue-name chatflow-db-dlq.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=false \
  --region us-west-2

# Get the queue URL (save this)
aws sqs get-queue-url \
  --queue-name chatflow-db-dlq.fifo \
  --region us-west-2
```

### 2. Create DLQ via AWS Console

1. Go to SQS Console
2. Click "Create queue"
3. **Type**: FIFO
4. **Name**: `chatflow-db-dlq.fifo`
5. **Configuration**:
   - Message retention: 14 days (maximum)
   - Visibility timeout: 30 seconds
   - Delivery delay: 0 seconds
   - Content-based deduplication: Disabled
6. Click "Create queue"

### 3. Set Permissions

The application IAM role needs these permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:GetQueueUrl",
        "sqs:GetQueueAttributes"
      ],
      "Resource": "arn:aws:sqs:us-west-2:*:chatflow-db-dlq.fifo"
    }
  ]
}
```

## Application Configuration

Update `application-assignment3.properties`:

```properties
# Enable DLQ (recommended for production)
dlq.enabled=true

# DLQ queue name
dlq.queue.name=chatflow-db-dlq.fifo
```

## DLQ Message Format

Messages in the DLQ have this structure:

```json
{
  "originalMessage": {
    "messageId": "uuid",
    "roomId": "1",
    "userId": "user-123",
    "username": "john",
    "message": "Hello!",
    "timestamp": "2024-01-01T00:00:00Z",
    "messageType": "TEXT",
    "serverId": "server-1",
    "clientIp": "192.168.1.1"
  },
  "failureReason": "Database write failure: Connection timeout",
  "failureTimestamp": 1704067200000,
  "attemptCount": 1
}
```

## Monitoring DLQ

### Check DLQ Depth

```bash
# Via AWS CLI
aws sqs get-queue-attributes \
  --queue-url https://sqs.us-west-2.amazonaws.com/ACCOUNT_ID/chatflow-db-dlq.fifo \
  --attribute-names ApproximateNumberOfMessages

# Via Application Health Endpoint
curl http://localhost:8080/db-metrics
```

### View DLQ Metrics

The `/db-metrics` endpoint includes:

```json
{
  "dlq": {
    "enabled": true,
    "queue_url": "https://sqs.us-west-2.amazonaws.com/.../chatflow-db-dlq.fifo",
    "messages_sent": 0,
    "send_failures": 0
  }
}
```

## Manual Recovery

### Option 1: Redrive Policy (Automatic)

Configure the DLQ with a redrive policy to automatically retry:

```bash
aws sqs set-queue-attributes \
  --queue-url <DLQ_URL> \
  --attributes file://redrive-policy.json
```

`redrive-policy.json`:
```json
{
  "RedrivePolicy": "{\"maxReceiveCount\":\"3\", \"deadLetterTargetArn\":\"arn:aws:sqs:us-west-2:ACCOUNT:chatflow-db-recovery\"}"
}
```

### Option 2: Manual Replay Script

```bash
# Python script to replay DLQ messages
python scripts/replay-dlq.py \
  --queue-url <DLQ_URL> \
  --database-endpoint <DB_ENDPOINT> \
  --batch-size 100
```

### Option 3: Lambda Function

Create a Lambda function triggered by DLQ to automatically retry writes:

```javascript
exports.handler = async (event) => {
  for (const record of event.Records) {
    const dlqMessage = JSON.parse(record.body);
    await retryDatabaseWrite(dlqMessage.originalMessage);
  }
};
```

## Testing DLQ

### 1. Simulate Database Failure

```bash
# Stop database temporarily
# Send messages
# Check DLQ depth
aws sqs get-queue-attributes \
  --queue-url <DLQ_URL> \
  --attribute-names ApproximateNumberOfMessages
```

### 2. Verify Message Format

```bash
# Receive one message from DLQ
aws sqs receive-message \
  --queue-url <DLQ_URL> \
  --max-number-of-messages 1 \
  --wait-time-seconds 5
```

## Production Recommendations

1. **Enable DLQ**: Always set `dlq.enabled=true` in production
2. **Monitor DLQ Depth**: Set CloudWatch alarm if depth > 1000
3. **Regular Cleanup**: Review and replay DLQ messages weekly
4. **Retention Period**: Set to 14 days (maximum)
5. **Alerting**: Alert operations team when messages enter DLQ

## Troubleshooting

### DLQ Not Working

**Symptom**: Messages lost, DLQ metrics show 0

**Checks**:
1. Verify `dlq.enabled=true`
2. Check application logs for "DLQ initialized"
3. Verify IAM permissions
4. Check queue name matches configuration

### DLQ Send Failures

**Symptom**: `dlq_send_failures` > 0

**Possible Causes**:
- IAM permission issues
- Queue doesn't exist
- Network connectivity
- Message size > 256KB

**Resolution**:
```bash
# Check IAM role
aws iam get-role --role-name <EC2_ROLE>

# Verify queue exists
aws sqs get-queue-url --queue-name chatflow-db-dlq.fifo
```

## Cost Considerations

- DLQ storage: $0.40 per million requests
- 14-day retention: Minimal storage cost
- Expected cost: < $1/month for typical usage

## Integration with Assignment 3

The DLQ is automatically integrated with:
- `BatchMessageWriter`: Sends failed batches to DLQ
- `DeadLetterQueueService`: Handles DLQ operations
- `/db-metrics`: Monitors DLQ status

No additional code changes needed - just configure AWS resources.
