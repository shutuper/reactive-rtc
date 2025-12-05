#!/bin/bash
#
# Gatling Load Test Runner
# Launches Gatling load tests in Kubernetes with custom parameters
#
# Usage:
#   ./run-gatling.sh [OPTIONS]
#
# Options:
#   -n, --name NAME       Test name/prefix (default: gatling-test)
#   -c, --clients NUM     Number of concurrent clients (default: 100)
#   -r, --rampup MIN      Ramp-up time in minutes (default: 1)
#   -d, --duration MIN    Test duration in minutes (default: 5)
#   -i, --interval SEC    Seconds between messages per client (default: 3)
#   -k, --keep-alive      Keep pod running after test (for debugging)
#   -h, --help            Show this help message
#
# Examples:
#   # Run with defaults
#   ./run-gatling.sh
#
#   # Run with custom name and 1000 clients
#   ./run-gatling.sh -n load-test-1 -c 1000
#
#   # Run multiple tests in parallel
#   ./run-gatling.sh -n test-a -c 500 &
#   ./run-gatling.sh -n test-b -c 500 &
#   ./run-gatling.sh -n test-c -c 500 &
#   wait
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
TEST_NAME="gatling-$(date +%s)"
CLIENTS=100
RAMPUP=1
DURATION=5
INTERVAL=3
KEEP_ALIVE="false"
NAMESPACE="rtc"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -n|--name)
            TEST_NAME="$2"
            shift 2
            ;;
        -c|--clients)
            CLIENTS="$2"
            shift 2
            ;;
        -r|--rampup)
            RAMPUP="$2"
            shift 2
            ;;
        -d|--duration)
            DURATION="$2"
            shift 2
            ;;
        -i|--interval)
            INTERVAL="$2"
            shift 2
            ;;
        -k|--keep-alive)
            KEEP_ALIVE="true"
            shift
            ;;
        -h|--help)
            echo "Gatling Load Test Runner"
            echo ""
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  -n, --name NAME       Test name/prefix (default: gatling-<timestamp>)"
            echo "  -c, --clients NUM     Number of concurrent clients (default: 100)"
            echo "  -r, --rampup MIN      Ramp-up time in minutes (default: 1)"
            echo "  -d, --duration MIN    Test duration in minutes (default: 5)"
            echo "  -i, --interval SEC    Seconds between messages per client (default: 3)"
            echo "  -k, --keep-alive      Keep pod running after test (for debugging)"
            echo "  -h, --help            Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0 -n my-test -c 1000 -d 10"
            echo "  $0 -n parallel-1 -c 500 &"
            echo "  $0 -n parallel-2 -c 500 &"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

# Sanitize test name (k8s naming rules)
TEST_NAME=$(echo "$TEST_NAME" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9-]/-/g' | sed 's/--*/-/g' | sed 's/^-//' | sed 's/-$//')
JOB_NAME="gatling-${TEST_NAME}"

# Check if kubectl is available
if command -v kubectl &> /dev/null; then
    KUBECTL="kubectl"
elif command -v minikube &> /dev/null; then
    KUBECTL="minikube kubectl --"
else
    echo -e "${RED}Error: kubectl not found${NC}"
    exit 1
fi

echo -e "${BLUE}╔═══════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║          Gatling Load Test - Kubernetes                           ║${NC}"
echo -e "${BLUE}╠═══════════════════════════════════════════════════════════════════╣${NC}"
echo -e "${BLUE}║${NC}  Job Name:      ${GREEN}${JOB_NAME}${NC}"
echo -e "${BLUE}║${NC}  Clients:       ${GREEN}${CLIENTS}${NC}"
echo -e "${BLUE}║${NC}  Ramp-up:       ${GREEN}${RAMPUP} minutes${NC}"
echo -e "${BLUE}║${NC}  Duration:      ${GREEN}${DURATION} minutes${NC}"
echo -e "${BLUE}║${NC}  Interval:      ${GREEN}${INTERVAL} seconds${NC}"
echo -e "${BLUE}║${NC}  Keep Alive:    ${GREEN}${KEEP_ALIVE}${NC}"
echo -e "${BLUE}╚═══════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check if cluster is ready
echo -e "${YELLOW}>>> Checking cluster readiness${NC}"
if ! $KUBECTL get pods -n $NAMESPACE -l app=nginx-gateway 2>/dev/null | grep -q "Running"; then
    echo -e "${RED}Error: Nginx gateway not running. Is the cluster deployed?${NC}"
    echo "Run: ./deploy.sh"
    exit 1
fi
echo -e "${GREEN}✓ Cluster is ready${NC}"

# Check if Gatling image exists
echo -e "${YELLOW}>>> Checking Gatling image${NC}"
if ! docker images | grep -q "local/reactive-rtc-gatling"; then
    echo -e "${YELLOW}Building Gatling image...${NC}"

    # Switch to minikube docker if using minikube
    if command -v minikube &> /dev/null && minikube status &> /dev/null; then
        eval $(minikube docker-env)
    fi

    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

    docker build -t local/reactive-rtc-gatling:latest \
        -f "$PROJECT_ROOT/gatling-load-test/Dockerfile" \
        "$PROJECT_ROOT/gatling-load-test"

    echo -e "${GREEN}✓ Gatling image built${NC}"
else
    echo -e "${GREEN}✓ Gatling image exists${NC}"
fi

# Delete existing job with same name (if any)
$KUBECTL delete job "$JOB_NAME" -n $NAMESPACE --ignore-not-found 2>/dev/null

# Create the job
echo -e "${YELLOW}>>> Creating Gatling job: ${JOB_NAME}${NC}"

cat <<EOF | $KUBECTL apply -f -
apiVersion: batch/v1
kind: Job
metadata:
  name: ${JOB_NAME}
  namespace: ${NAMESPACE}
  labels:
    app: gatling-loadtest
    test-name: ${TEST_NAME}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 3600
  template:
    metadata:
      labels:
        app: gatling-loadtest
        test-name: ${TEST_NAME}
    spec:
      restartPolicy: Never

      securityContext:
        sysctls:
          - name: net.ipv4.ip_local_port_range
            value: "1024 65535"

      containers:
        - name: gatling
          image: local/reactive-rtc-gatling:latest
          imagePullPolicy: Never

          env:
            - name: CLIENTS
              value: "${CLIENTS}"
            - name: RAMPUP
              value: "${RAMPUP}"
            - name: DURATION
              value: "${DURATION}"
            - name: INTERVAL
              value: "${INTERVAL}"
            - name: GATEWAY_URL
              value: "http://nginx-gateway-service.rtc.svc.cluster.local"
            - name: WS_GATEWAY_URL
              value: "ws://nginx-gateway-service.rtc.svc.cluster.local"
            - name: KEEP_ALIVE
              value: "${KEEP_ALIVE}"
            - name: CLIENT_PREFIX
              value: "${TEST_NAME}"

          resources:
            requests:
              cpu: 1000m
              memory: 1Gi
            limits:
              cpu: 2000m
              memory: 2Gi

          volumeMounts:
            - name: results
              mountPath: /app/results

      volumes:
        - name: results
          emptyDir: {}
EOF

echo -e "${GREEN}✓ Job created${NC}"
echo ""

# Wait for pod to start
echo -e "${YELLOW}>>> Waiting for pod to start...${NC}"
POD_NAME=""
for i in {1..30}; do
    POD_NAME=$($KUBECTL get pods -n $NAMESPACE -l "job-name=${JOB_NAME}" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
    if [ -n "$POD_NAME" ]; then
        POD_STATUS=$($KUBECTL get pod -n $NAMESPACE "$POD_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || true)
        if [ "$POD_STATUS" = "Running" ] || [ "$POD_STATUS" = "Succeeded" ] || [ "$POD_STATUS" = "Failed" ]; then
            break
        fi
    fi
    sleep 2
done

if [ -z "$POD_NAME" ]; then
    echo -e "${RED}Error: Pod not created${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Pod started: ${POD_NAME}${NC}"
echo ""

# Show logs
echo -e "${YELLOW}>>> Streaming logs (Ctrl+C to detach, test will continue)${NC}"
echo ""
echo "═══════════════════════════════════════════════════════════════════"

# Stream logs
$KUBECTL logs -n $NAMESPACE -f "$POD_NAME" 2>/dev/null || true

# Check final status
echo ""
echo "═══════════════════════════════════════════════════════════════════"
POD_STATUS=$($KUBECTL get pod -n $NAMESPACE "$POD_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || echo "Unknown")
if [ "$POD_STATUS" = "Succeeded" ]; then
    echo -e "${GREEN}✓ Test completed successfully!${NC}"
elif [ "$POD_STATUS" = "Failed" ]; then
    echo -e "${RED}✗ Test failed${NC}"
else
    echo -e "${YELLOW}Test status: ${POD_STATUS}${NC}"
fi

echo ""
echo "Useful commands:"
echo "  # View logs:"
echo "  $KUBECTL logs -n $NAMESPACE $POD_NAME"
echo ""
echo "  # Copy results:"
echo "  $KUBECTL cp $NAMESPACE/$POD_NAME:/app/results ./gatling-results-${TEST_NAME}"
echo ""
echo "  # Delete job:"
echo "  $KUBECTL delete job $JOB_NAME -n $NAMESPACE"

