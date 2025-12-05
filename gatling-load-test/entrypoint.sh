#!/bin/bash
set -e

echo "╔═══════════════════════════════════════════════════════════════════╗"
echo "║          Gatling Load Test - Kubernetes                           ║"
echo "╠═══════════════════════════════════════════════════════════════════╣"
echo "║  Gateway URL:     ${GATEWAY_URL}"
echo "║  WS Gateway URL:  ${WS_GATEWAY_URL}"
echo "║  Clients:         ${CLIENTS}"
echo "║  Ramp-up:         ${RAMPUP} minutes"
echo "║  Duration:        ${DURATION} minutes"
echo "║  Interval:        ${INTERVAL} seconds"
echo "║  Client Prefix:   ${CLIENT_PREFIX:-k8s}"
echo "╚═══════════════════════════════════════════════════════════════════╝"
echo ""

# Wait for nginx gateway to be ready
echo "Waiting for nginx gateway to be ready..."
MAX_RETRIES=30
RETRY_COUNT=0
until curl -sf "${GATEWAY_URL}/healthz" > /dev/null 2>&1; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        echo "ERROR: Nginx gateway not ready after ${MAX_RETRIES} attempts"
        exit 1
    fi
    echo "  Attempt ${RETRY_COUNT}/${MAX_RETRIES}..."
    sleep 2
done
echo "✓ Nginx gateway is ready"

# Wait for load-balancer API
echo "Waiting for load-balancer API..."
RETRY_COUNT=0
until curl -sf "${GATEWAY_URL}/api/v1/resolve?clientId=healthcheck" | grep -q "nodeId"; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        echo "ERROR: Load-balancer API not ready after ${MAX_RETRIES} attempts"
        exit 1
    fi
    echo "  Attempt ${RETRY_COUNT}/${MAX_RETRIES}..."
    sleep 2
done
echo "✓ Load-balancer API is ready"

echo ""
echo "Starting Gatling test..."
echo ""

# Find the jar file
JAR_FILE=$(ls /app/gatling-load-test-*.jar 2>/dev/null | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "ERROR: Could not find gatling-load-test jar"
    exit 1
fi
echo "Using jar: ${JAR_FILE}"

# Configure JVM options
JAVA_OPTS="-Xms512m -Xmx2g"
JAVA_OPTS="$JAVA_OPTS -XX:+UseZGC"
JAVA_OPTS="$JAVA_OPTS -XX:MaxDirectMemorySize=1g"
JAVA_OPTS="$JAVA_OPTS -Djava.net.preferIPv4Stack=true"

# Gatling system properties
GATLING_OPTS="-Dgateway.url=${GATEWAY_URL}"
GATLING_OPTS="$GATLING_OPTS -Dws.gateway.url=${WS_GATEWAY_URL}"
GATLING_OPTS="$GATLING_OPTS -Dclients=${CLIENTS}"
GATLING_OPTS="$GATLING_OPTS -Drampup=${RAMPUP}"
GATLING_OPTS="$GATLING_OPTS -Dduration=${DURATION}"
GATLING_OPTS="$GATLING_OPTS -Dinterval=${INTERVAL}"
GATLING_OPTS="$GATLING_OPTS -Dclient.prefix=${CLIENT_PREFIX:-k8s}"

# Run Gatling
exec java $JAVA_OPTS $GATLING_OPTS \
    -cp "${JAR_FILE}:/app/lib/*" \
    io.gatling.app.Gatling \
    -s com.qqsuccubus.loadtest.ReactiveRtcLoadTest \
    -rf /app/results

