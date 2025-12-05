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
echo "  MESSAGE_INTERVAL:  ${MESSAGE_INTERVAL_MS}ms (${MESSAGE_INTERVAL_MS:-5000} / 1000 = interval in seconds)"
echo "  CLIENT_PREFIX:     ${CLIENT_PREFIX}"
echo ""

# Set defaults if not provided
TOTAL_CLIENTS=${TOTAL_CLIENTS:-100}
RAMPUP_MINUTES=${RAMPUP_MINUTES:-1}
DURATION_MINUTES=${DURATION_MINUTES:-5}
MESSAGE_INTERVAL_MS=${MESSAGE_INTERVAL_MS:-5000}
CLIENT_PREFIX=${CLIENT_PREFIX:-docker}

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

# Java 17+ module system opens required by Gatling
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.lang=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.lang.invoke=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.io=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.nio=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.util=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/sun.security.ssl=ALL-UNNAMED"

# Convert message interval from ms to seconds
MESSAGE_INTERVAL_SEC=$((MESSAGE_INTERVAL_MS / 1000))
if [ "$MESSAGE_INTERVAL_SEC" -lt 1 ]; then
    MESSAGE_INTERVAL_SEC=1
fi

# Gatling system properties (must match ReactiveRtcLoadTest.java)
GATLING_OPTS="-Dgateway.url=${GATEWAY_URL}"
GATLING_OPTS="$GATLING_OPTS -Dws.gateway.url=${WS_GATEWAY_URL}"
GATLING_OPTS="$GATLING_OPTS -Dclients=${TOTAL_CLIENTS}"
GATLING_OPTS="$GATLING_OPTS -Drampup=${RAMPUP_MINUTES}"
GATLING_OPTS="$GATLING_OPTS -Dduration=${DURATION_MINUTES}"
GATLING_OPTS="$GATLING_OPTS -Dinterval=${MESSAGE_INTERVAL_SEC}"
GATLING_OPTS="$GATLING_OPTS -Dclient.prefix=${CLIENT_PREFIX}"

echo "Starting Gatling test..."
echo "  JVM Options: ${JAVA_OPTS}"
echo "  Gatling Options: ${GATLING_OPTS}"
echo ""
echo "=========================================="
echo ""

# Find the jar file
echo "Checking jar files..."
ls -la /app/*.jar 2>/dev/null || echo "  No jar files in /app"
ls -la /app/lib/ 2>/dev/null | head -5 || echo "  No lib directory or empty"

JAR_FILE=$(ls /app/gatling-load-test-*.jar 2>/dev/null | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "ERROR: Could not find gatling-load-test jar"
    echo "Contents of /app:"
    ls -la /app/
    exit 1
fi
echo "Using jar: ${JAR_FILE}"
echo ""

# Run Gatling
exec java $JAVA_OPTS $GATLING_OPTS \
    -cp "${JAR_FILE}:/app/lib/*" \
    io.gatling.app.Gatling \
    -s com.qqsuccubus.loadtest.ReactiveRtcLoadTest \
    -rf /app/results





