#!/bin/bash
#
# Run Gatling Load Tests against Reactive RTC
# Can run single or multiple parallel test instances
#
# Usage:
#   ./run-tests.sh -h 192.168.1.100              # Single test against host
#   ./run-tests.sh -h 192.168.1.100 -n 3         # 3 parallel tests
#   ./run-tests.sh -h 192.168.1.100 -c 10000 -n 5  # 5 tests, 10K clients each
#
# Examples:
#   # Basic test (100 clients, 5 minutes)
#   ./run-tests.sh -h 192.168.1.100
#
#   # Heavy load test (50K clients per instance, 3 instances = 150K total)
#   ./run-tests.sh -h 192.168.1.100 -c 50000 -n 3 -d 10 -r 3
#
#   # Maximum stress test (100K clients per instance, 10 instances = 1M total)
#   ./run-tests.sh -h 192.168.1.100 -c 100000 -n 10 -d 15 -r 5

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Default values
TARGET_HOST=""
TARGET_PORT="8080"
WS_PROTOCOL="ws"
TOTAL_CLIENTS="1000"
RAMPUP_MINUTES="2"
DURATION_MINUTES="5"
MESSAGE_INTERVAL_MS="5000"
NUM_INSTANCES="1"
BUILD_IMAGE="false"

print_usage() {
    echo "Usage: $0 -h <host> [options]"
    echo ""
    echo "Required:"
    echo "  -h, --host HOST       Target host IP or hostname (e.g., 192.168.1.100)"
    echo ""
    echo "Options:"
    echo "  -p, --port PORT       Target port (default: 8080)"
    echo "  -c, --clients NUM     Total clients per instance (default: 1000)"
    echo "  -r, --rampup MIN      Ramp-up time in minutes (default: 2)"
    echo "  -d, --duration MIN    Test duration in minutes (default: 5)"
    echo "  -i, --interval MS     Message interval in ms (default: 5000)"
    echo "  -n, --instances NUM   Number of parallel test instances (default: 1)"
    echo "  -s, --secure          Use WSS/HTTPS instead of WS/HTTP"
    echo "  -b, --build           Force rebuild Docker image"
    echo "  --help                Show this help"
    echo ""
    echo "Examples:"
    echo "  # Basic test"
    echo "  $0 -h 192.168.1.100"
    echo ""
    echo "  # 3 parallel instances, 10K clients each (30K total)"
    echo "  $0 -h 192.168.1.100 -c 10000 -n 3"
    echo ""
    echo "  # Heavy load: 5 instances, 50K clients each (250K total), 10 min"
    echo "  $0 -h 192.168.1.100 -c 50000 -n 5 -d 10 -r 3"
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--host)
            TARGET_HOST="$2"
            shift 2
            ;;
        -p|--port)
            TARGET_PORT="$2"
            shift 2
            ;;
        -c|--clients)
            TOTAL_CLIENTS="$2"
            shift 2
            ;;
        -r|--rampup)
            RAMPUP_MINUTES="$2"
            shift 2
            ;;
        -d|--duration)
            DURATION_MINUTES="$2"
            shift 2
            ;;
        -i|--interval)
            MESSAGE_INTERVAL_MS="$2"
            shift 2
            ;;
        -n|--instances)
            NUM_INSTANCES="$2"
            shift 2
            ;;
        -s|--secure)
            WS_PROTOCOL="wss"
            shift
            ;;
        -b|--build)
            BUILD_IMAGE="true"
            shift
            ;;
        --help)
            print_usage
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            print_usage
            exit 1
            ;;
    esac
done

# Validate required arguments
if [ -z "$TARGET_HOST" ]; then
    echo -e "${RED}Error: Target host is required${NC}"
    echo ""
    print_usage
    exit 1
fi

# Calculate totals
TOTAL_CONNECTIONS=$((TOTAL_CLIENTS * NUM_INSTANCES))

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Reactive RTC - Gatling Load Test     ${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${GREEN}Test Configuration:${NC}"
echo "  Target:           ${TARGET_HOST}:${TARGET_PORT}"
echo "  Protocol:         ${WS_PROTOCOL}"
echo "  Clients/instance: ${TOTAL_CLIENTS}"
echo "  Instances:        ${NUM_INSTANCES}"
echo "  Total connections:${TOTAL_CONNECTIONS}"
echo "  Ramp-up:          ${RAMPUP_MINUTES} minutes"
echo "  Duration:         ${DURATION_MINUTES} minutes"
echo "  Message interval: ${MESSAGE_INTERVAL_MS}ms"
echo ""

# Test connectivity first
echo -e "${GREEN}Testing connectivity...${NC}"
if nc -z -w5 ${TARGET_HOST} ${TARGET_PORT} 2>/dev/null; then
    echo -e "  ${GREEN}✓${NC} ${TARGET_HOST}:${TARGET_PORT} is reachable"
else
    echo -e "  ${RED}✗${NC} Cannot connect to ${TARGET_HOST}:${TARGET_PORT}"
    echo ""
    echo "Make sure:"
    echo "  1. The target host is running and accessible"
    echo "  2. Firewall allows connections on port ${TARGET_PORT}"
    echo "  3. The nginx service is exposed (see instructions below)"
    exit 1
fi

# Test API endpoint
echo "Testing API..."
RESOLVE_URL="http://${TARGET_HOST}:${TARGET_PORT}/api/v1/resolve?clientId=test"
RESPONSE=$(curl -s --connect-timeout 5 "$RESOLVE_URL" 2>/dev/null || echo "FAILED")
if [[ "$RESPONSE" == *"nodeId"* ]]; then
    echo -e "  ${GREEN}✓${NC} API is responding"
else
    echo -e "  ${YELLOW}⚠${NC} API returned: $RESPONSE"
fi
echo ""

# Create results directory
RESULTS_DIR="./results/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"
echo "Results will be saved to: ${RESULTS_DIR}"
echo ""

# Build or pull image if needed
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ "$BUILD_IMAGE" = "true" ] || ! docker images | grep -q "gatling-load-test"; then
    echo -e "${GREEN}Building Docker image...${NC}"
    docker build -t gatling-load-test:latest -f Dockerfile ../..
    echo ""
fi

# Run the tests
echo -e "${GREEN}Starting ${NUM_INSTANCES} test instance(s)...${NC}"
echo ""

PIDS=()
for i in $(seq 1 $NUM_INSTANCES); do
    PREFIX="test-${i}-$(hostname -s 2>/dev/null || echo 'docker')"
    CONTAINER_NAME="gatling-test-${i}"
    
    echo "Starting instance ${i}/${NUM_INSTANCES} (prefix: ${PREFIX})..."
    
    docker run -d \
        --name "${CONTAINER_NAME}" \
        --rm \
        -e TARGET_HOST="${TARGET_HOST}" \
        -e TARGET_PORT="${TARGET_PORT}" \
        -e WS_PROTOCOL="${WS_PROTOCOL}" \
        -e TOTAL_CLIENTS="${TOTAL_CLIENTS}" \
        -e RAMPUP_MINUTES="${RAMPUP_MINUTES}" \
        -e DURATION_MINUTES="${DURATION_MINUTES}" \
        -e MESSAGE_INTERVAL_MS="${MESSAGE_INTERVAL_MS}" \
        -e CLIENT_PREFIX="${PREFIX}" \
        -v "${RESULTS_DIR}:/app/results" \
        --ulimit nofile=1048576:1048576 \
        --sysctl net.ipv4.ip_local_port_range="1024 65535" \
        gatling-load-test:latest
    
    PIDS+=("${CONTAINER_NAME}")
done

echo ""
echo -e "${GREEN}All instances started!${NC}"
echo ""
echo "Monitor logs:"
for container in "${PIDS[@]}"; do
    echo "  docker logs -f ${container}"
done
echo ""
echo "Or follow all:"
echo "  docker logs -f ${PIDS[0]}"
echo ""

# Wait for completion
echo "Waiting for tests to complete..."
echo "(Press Ctrl+C to stop all tests)"
echo ""

# Trap Ctrl+C to stop all containers
cleanup() {
    echo ""
    echo -e "${YELLOW}Stopping all test containers...${NC}"
    for container in "${PIDS[@]}"; do
        docker stop "${container}" 2>/dev/null || true
    done
    exit 0
}
trap cleanup SIGINT SIGTERM

# Follow logs from first container and wait for all
docker logs -f "${PIDS[0]}" 2>/dev/null &
LOG_PID=$!

# Wait for all containers to finish
for container in "${PIDS[@]}"; do
    docker wait "${container}" 2>/dev/null || true
done

kill $LOG_PID 2>/dev/null || true

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Tests Complete!                       ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Results saved to: ${RESULTS_DIR}"
echo ""





