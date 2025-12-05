#!/bin/bash
# Gatling Load Test Entrypoint
# Connects to a remote Reactive RTC instance

set -e

echo "=========================================="
echo "  Reactive RTC - Gatling Load Test"
echo "=========================================="
echo ""

# Configuration from environment
echo "Configuration:"
echo "  TARGET_HOST:       ${TARGET_HOST}"
echo "  TARGET_PORT:       ${TARGET_PORT}"
echo "  WS_PROTOCOL:       ${WS_PROTOCOL}"
echo "  TOTAL_CLIENTS:     ${TOTAL_CLIENTS}"
echo "  RAMPUP_MINUTES:    ${RAMPUP_MINUTES}"
echo "  DURATION_MINUTES:  ${DURATION_MINUTES}"
echo "  MESSAGE_INTERVAL:  ${MESSAGE_INTERVAL_MS}ms"
echo "  CLIENT_PREFIX:     ${CLIENT_PREFIX}"
echo ""

# Build the gateway URL
GATEWAY_URL="http://${TARGET_HOST}:${TARGET_PORT}"
WS_GATEWAY_URL="${WS_PROTOCOL}://${TARGET_HOST}:${TARGET_PORT}"

echo "Gateway URLs:"
echo "  HTTP: ${GATEWAY_URL}"
echo "  WS:   ${WS_GATEWAY_URL}"
echo ""

# Test connectivity
echo "Testing connectivity to ${TARGET_HOST}:${TARGET_PORT}..."
MAX_RETRIES=30
RETRY_COUNT=0

while ! nc -z ${TARGET_HOST} ${TARGET_PORT} 2>/dev/null; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        echo "ERROR: Cannot connect to ${TARGET_HOST}:${TARGET_PORT} after ${MAX_RETRIES} attempts"
        exit 1
    fi
    echo "  Waiting for ${TARGET_HOST}:${TARGET_PORT}... (attempt ${RETRY_COUNT}/${MAX_RETRIES})"
    sleep 2
done
echo "✓ Connection successful"
echo ""

# Test the API endpoint
echo "Testing API endpoint..."
RESOLVE_RESPONSE=$(curl -s "${GATEWAY_URL}/api/v1/resolve?clientId=test-${CLIENT_PREFIX}" 2>/dev/null || echo "FAILED")
if [[ "$RESOLVE_RESPONSE" == *"nodeId"* ]]; then
    echo "✓ API is responding: ${RESOLVE_RESPONSE}"
else
    echo "⚠ API test returned: ${RESOLVE_RESPONSE}"
    echo "  Continuing anyway..."
fi
echo ""

# Set ulimits for high connection count
echo "Setting ulimits..."
ulimit -n 1048576 2>/dev/null || echo "  Could not set nofile limit (current: $(ulimit -n))"
echo "  Open files limit: $(ulimit -n)"
echo ""

# Configure JVM options
JAVA_OPTS="-Xms512m -Xmx4g"
JAVA_OPTS="$JAVA_OPTS -XX:+UseZGC"
JAVA_OPTS="$JAVA_OPTS -XX:MaxDirectMemorySize=2g"
JAVA_OPTS="$JAVA_OPTS -Djava.net.preferIPv4Stack=true"

# Gatling system properties
GATLING_OPTS="-Dgateway.url=${GATEWAY_URL}"
GATLING_OPTS="$GATLING_OPTS -Dws.gateway.url=${WS_GATEWAY_URL}"
GATLING_OPTS="$GATLING_OPTS -Dtotal.clients=${TOTAL_CLIENTS}"
GATLING_OPTS="$GATLING_OPTS -Drampup.minutes=${RAMPUP_MINUTES}"
GATLING_OPTS="$GATLING_OPTS -Dduration.minutes=${DURATION_MINUTES}"
GATLING_OPTS="$GATLING_OPTS -Dmessage.interval.ms=${MESSAGE_INTERVAL_MS}"
GATLING_OPTS="$GATLING_OPTS -Dclient.prefix=${CLIENT_PREFIX}"

echo "Starting Gatling test..."
echo "  JVM Options: ${JAVA_OPTS}"
echo "  Gatling Options: ${GATLING_OPTS}"
echo ""
echo "=========================================="
echo ""

# Find the jar file
JAR_FILE=$(ls /app/gatling-load-test-*.jar 2>/dev/null | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "ERROR: Could not find gatling-load-test jar"
    exit 1
fi

# Run Gatling
exec java $JAVA_OPTS $GATLING_OPTS \
    -cp "${JAR_FILE}:/app/lib/*" \
    io.gatling.app.Gatling \
    -s com.qqsuccubus.loadtest.ReactiveRtcLoadTest \
    -rf /app/results





