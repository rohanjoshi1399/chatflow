#!/bin/bash

# ChatFlow Queue Monitoring Script
# Continuously monitors all 20 SQS queues and displays their depths

REGION="us-east-1"
QUEUE_PREFIX="chatflow-room-"
REFRESH_INTERVAL=5  # seconds

# Colors for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to get queue depth
get_queue_depth() {
    local room_id=$1
    local queue_name="${QUEUE_PREFIX}${room_id}.fifo"
    
    queue_url=$(aws sqs get-queue-url --queue-name "$queue_name" --region "$REGION" --query 'QueueUrl' --output text 2>/dev/null)
    
    if [ $? -ne 0 ]; then
        echo "ERROR"
        return
    fi
    
    attributes=$(aws sqs get-queue-attributes \
        --queue-url "$queue_url" \
        --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
        --region "$REGION" \
        --query 'Attributes' \
        --output json 2>/dev/null)
    
    if [ $? -ne 0 ]; then
        echo "ERROR"
        return
    fi
    
    visible=$(echo "$attributes" | jq -r '.ApproximateNumberOfMessages // "0"')
    not_visible=$(echo "$attributes" | jq -r '.ApproximateNumberOfMessagesNotVisible // "0"')
    
    total=$((visible + not_visible))
    
    echo "$visible:$not_visible:$total"
}

# Main monitoring loop
echo "Starting ChatFlow Queue Monitor..."
echo "Press Ctrl+C to stop"
echo ""

while true; do
    clear
    echo "======================================================================"
    echo "ChatFlow SQS Queue Monitor - $(date '+%Y-%m-%d %H:%M:%S')"
    echo "======================================================================"
    echo ""
    printf "%-8s %-12s %-15s %-10s\n" "Room" "Visible" "Not Visible" "Total"
    echo "----------------------------------------------------------------------"
    
    total_visible=0
    total_not_visible=0
    total_messages=0
    max_depth=0
    max_room=""
    
    for i in {1..20}; do
        result=$(get_queue_depth $i)
        
        if [ "$result" == "ERROR" ]; then
            printf "%-8s %-12s %-15s %-10s\n" "Room $i" "ERROR" "ERROR" "ERROR"
            continue
        fi
        
        IFS=':' read -r visible not_visible total <<< "$result"
        
        total_visible=$((total_visible + visible))
        total_not_visible=$((total_not_visible + not_visible))
        total_messages=$((total_messages + total))
        
        if [ $total -gt $max_depth ]; then
            max_depth=$total
            max_room="Room $i"
        fi
        
        # Color code based on depth
        if [ $total -gt 1000 ]; then
            color=$RED
        elif [ $total -gt 500 ]; then
            color=$YELLOW
        else
            color=$GREEN
        fi
        
        printf "${color}%-8s %-12s %-15s %-10s${NC}\n" "Room $i" "$visible" "$not_visible" "$total"
    done
    
    echo "----------------------------------------------------------------------"
    printf "%-8s %-12s %-15s %-10s\n" "TOTAL" "$total_visible" "$total_not_visible" "$total_messages"
    echo "======================================================================"
    echo ""
    echo "Maximum Queue Depth: $max_room with $max_depth messages"
    echo ""
    echo "Status: "
    if [ $total_messages -lt 500 ]; then
        echo -e "${GREEN}Healthy - Low queue depth${NC}"
    elif [ $total_messages -lt 2000 ]; then
        echo -e "${YELLOW}Warning - Moderate queue depth${NC}"
    else
        echo -e "${RED}Critical - High queue depth (consider adding consumers)${NC}"
    fi
    
    echo ""
    echo "Refreshing in $REFRESH_INTERVAL seconds... (Ctrl+C to stop)"
    
    sleep $REFRESH_INTERVAL
done