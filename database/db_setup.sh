#!/bin/bash

# ============================================================
# Aurora PostgreSQL Setup Script - Assignment 3
# ============================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
DB_CLUSTER_ID="cs6650-chatflow"
DB_INSTANCE_ID="cs6650-chatflow-instance-1"
DB_ENGINE="aurora-postgresql"
DB_ENGINE_VERSION="15.14"
DB_INSTANCE_CLASS="db.r6g.large"
DB_NAME="chatdb"
DB_USERNAME="db_admin"
DB_PASSWORD=""  # Will prompt for this
AWS_REGION="us-west-2"

echo "╔════════════════════════════════════════════════════════════╗"
echo "║        Aurora PostgreSQL Setup - Assignment 3              ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
    echo -e "${RED}❌ AWS CLI not found. Please install it first.${NC}"
    exit 1
fi

# Check AWS credentials
if ! aws sts get-caller-identity &> /dev/null; then
    echo -e "${RED}❌ AWS credentials not configured. Run 'aws configure' first.${NC}"
    exit 1
fi

echo -e "${GREEN}✅ AWS CLI configured${NC}"

# Prompt for database password
if [ -z "$DB_PASSWORD" ]; then
    echo ""
    echo "Enter database password (min 8 characters):"
    read -s DB_PASSWORD
    echo ""
fi

if [ ${#DB_PASSWORD} -lt 8 ]; then
    echo -e "${RED}❌ Password must be at least 8 characters${NC}"
    exit 1
fi

echo ""
echo "Configuration:"
echo "  Cluster ID: $DB_CLUSTER_ID"
echo "  Instance ID: $DB_INSTANCE_ID"
echo "  Instance Class: $DB_INSTANCE_CLASS"
echo "  Engine: $DB_ENGINE $DB_ENGINE_VERSION"
echo "  Region: $AWS_REGION"
echo "  Database Name: $DB_NAME"
echo ""

read -p "Continue with Aurora setup? (y/n) " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Setup cancelled."
    exit 0
fi

echo ""
echo "════════════════════════════════════════════════════════════"
echo "Step 1: Creating Aurora DB Cluster"
echo "════════════════════════════════════════════════════════════"

aws rds create-db-cluster \
    --db-cluster-identifier "$DB_CLUSTER_ID" \
    --engine "$DB_ENGINE" \
    --engine-version "$DB_ENGINE_VERSION" \
    --master-username "$DB_USERNAME" \
    --master-user-password "$DB_PASSWORD" \
    --database-name "$DB_NAME" \
    --region "$AWS_REGION" \
    --backup-retention-period 1 \
    --enable-cloudwatch-logs-exports "postgresql" \
    --no-enable-http-endpoint

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Aurora cluster created${NC}"
else
    echo -e "${RED}❌ Failed to create cluster${NC}"
    exit 1
fi

echo ""
echo "════════════════════════════════════════════════════════════"
echo "Step 2: Creating Aurora DB Instance"
echo "════════════════════════════════════════════════════════════"

aws rds create-db-instance \
    --db-instance-identifier "$DB_INSTANCE_ID" \
    --db-instance-class "$DB_INSTANCE_CLASS" \
    --engine "$DB_ENGINE" \
    --db-cluster-identifier "$DB_CLUSTER_ID" \
    --publicly-accessible \
    --region "$AWS_REGION"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Aurora instance created${NC}"
else
    echo -e "${RED}❌ Failed to create instance${NC}"
    exit 1
fi

echo ""
echo "════════════════════════════════════════════════════════════"
echo "Step 3: Waiting for Aurora to become available (this takes ~10 minutes)"
echo "════════════════════════════════════════════════════════════"

echo "Waiting for cluster..."
aws rds wait db-cluster-available \
    --db-cluster-identifier "$DB_CLUSTER_ID" \
    --region "$AWS_REGION"

echo "Waiting for instance..."
aws rds wait db-instance-available \
    --db-instance-identifier "$DB_INSTANCE_ID" \
    --region "$AWS_REGION"

echo -e "${GREEN}✅ Aurora is now available${NC}"

echo ""
echo "════════════════════════════════════════════════════════════"
echo "Step 4: Getting Aurora Endpoint"
echo "════════════════════════════════════════════════════════════"

AURORA_ENDPOINT=$(aws rds describe-db-clusters \
    --db-cluster-identifier "$DB_CLUSTER_ID" \
    --region "$AWS_REGION" \
    --query 'DBClusters[0].Endpoint' \
    --output text)

echo -e "${GREEN}✅ Aurora Endpoint: $AURORA_ENDPOINT${NC}"

echo ""
echo "════════════════════════════════════════════════════════════"
echo "Step 5: Connection Information"
echo "════════════════════════════════════════════════════════════"

echo ""
echo "Add this to your application.properties:"
echo ""
echo "spring.datasource.url=jdbc:postgresql://$AURORA_ENDPOINT:5432/$DB_NAME"
echo "spring.datasource.username=$DB_USERNAME"
echo "spring.datasource.password=<your-password>"
echo ""
echo "Or set as environment variable:"
echo "export DB_PASSWORD=\"<your-password>\""
echo ""

echo "════════════════════════════════════════════════════════════"
echo "Step 6: Connect and Initialize Schema"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "To connect to Aurora and initialize schema:"
echo ""
echo "1. Install psql client:"
echo "   brew install postgresql  # macOS"
echo "   sudo apt install postgresql-client  # Ubuntu"
echo ""
echo "2. Connect:"
echo "   psql -h $AURORA_ENDPOINT -U $DB_USERNAME -d $DB_NAME"
echo ""
echo "3. Run schema:"
echo "   psql -h $AURORA_ENDPOINT -U $DB_USERNAME -d $DB_NAME < schema.sql"
echo ""

echo "════════════════════════════════════════════════════════════"
echo " IMPORTANT: Security Group Configuration"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "You need to configure the security group to allow connections:"
echo ""
echo "1. Go to AWS Console → RDS → $DB_CLUSTER_ID"
echo "2. Click on VPC security group"
echo "3. Add inbound rule:"
echo "   - Type: PostgreSQL"
echo "   - Port: 5432"
echo "   - Source: Your EC2 security group (for server access)"
echo "   - Source: Your IP (for local testing)"
echo ""

echo "╔════════════════════════════════════════════════════════════╗"
echo "║             Aurora Setup Complete!                         ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo "Next steps:"
echo "1. Configure security group (see above)"
echo "2. Initialize database schema: psql ... < schema.sql"
echo "3. Update application.properties with Aurora endpoint"
echo "4. Deploy updated server application"
echo ""
echo -e "${YELLOW}⚠️  COST: ~$7-8 per 24 hours. Remember to delete after testing!${NC}"
echo ""

export DB_HOST="cs6650-chatflow.cluster-<>.us-west-2.rds.amazonaws.com"
export DB_NAME="chatdb"
export DB_USER="db_admin"
export DB_PASSWORD="admin1234"