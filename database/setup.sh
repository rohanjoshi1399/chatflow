#!/bin/bash
# ChatFlow Database Setup Script
# Purpose: Initialize Aurora PostgreSQL database with schema
# Usage: ./setup.sh

set -e  # Exit on error

# ============================================================================
# Configuration
# ============================================================================
DB_HOST="${DB_HOST:-chatflow-pg-cluster.cluster-xxxxx.us-west-2.rds.amazonaws.com}"
DB_NAME="${DB_NAME:-chatflow}"
DB_USER="${DB_USER:-db_admin}"
DB_PASSWORD="${DB_PASSWORD}"

if [ -z "$DB_PASSWORD" ]; then
    echo "Error: DB_PASSWORD environment variable not set"
    echo "Usage: export DB_PASSWORD=your_password && ./setup.sh"
    exit 1
fi

# ============================================================================
# Functions
# ============================================================================
log_info() {
    echo "[INFO] $1"
}

log_success() {
    echo "[SUCCESS] $1"
}

log_error() {
    echo "[ERROR] $1"
}

execute_sql() {
    local sql_file=$1
    log_info "Executing $sql_file..."
    PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -f "$sql_file"
    if [ $? -eq 0 ]; then
        log_success "$sql_file executed successfully"
    else
        log_error "Failed to execute $sql_file"
        exit 1
    fi
}

# ============================================================================
# Main Execution
# ============================================================================
log_info "Starting ChatFlow database setup..."
log_info "Database: $DB_NAME @ $DB_HOST"
log_info "User: $DB_USER"

# Check if psql is installed
if ! command -v psql &> /dev/null; then
    log_error "psql command not found. Please install PostgreSQL client."
    exit 1
fi

# Test connection
log_info "Testing database connection..."
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c "SELECT version();" > /dev/null
if [ $? -ne 0 ]; then
    log_error "Failed to connect to database"
    exit 1
fi
log_success "Database connection successful"

# Execute schema files in order
log_info "Creating schema..."
execute_sql "01_tables.sql"
execute_sql "02_indexes.sql"
execute_sql "03_materialized_views.sql"

# Verify setup
log_info "Verifying setup..."
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME << EOF
SELECT 'Tables created: ' || count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
SELECT 'Indexes created: ' || count(*) FROM pg_indexes WHERE schemaname = 'public';
SELECT 'Materialized views created: ' || count(*) FROM pg_matviews WHERE schemaname = 'public';
EOF

log_success "Database setup complete!"
log_info "Next steps:"
log_info "  1. Configure application.properties with connection details"
log_info "  2. Run consumer-v3 to start persisting messages"
log_info "  3. After load tests, refresh materialized views"
