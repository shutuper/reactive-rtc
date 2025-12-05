#!/bin/bash
#
# Expose Reactive RTC to Local Network
# Allows other PCs on your network to connect to the minikube cluster
# While keeping localhost access to all services (Grafana, Prometheus, etc.)
#
# Usage:
#   ./expose-to-network.sh
#
# This script will:
# 1. Find your local network IP
# 2. Start ALL port forwards (nginx, prometheus, grafana)
# 3. Nginx is exposed to network (0.0.0.0), others stay on localhost
# 4. Display connection information
#

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

printf "${BLUE}========================================${NC}\n"
printf "${BLUE}  Expose Reactive RTC to Network       ${NC}\n"
printf "${BLUE}========================================${NC}\n"
echo ""

# Find local IP address
get_local_ip() {
    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS - try multiple interfaces (common ones + thunderbolt/USB adapters)
        for iface in en0 en1 en2 en3 en4 en5 en6 en7 en8 en9 en10; do
            ip=$(ipconfig getifaddr $iface 2>/dev/null)
            if [ -n "$ip" ] && [ "$ip" != "127.0.0.1" ] && [[ ! "$ip" =~ ^192\.168\.64\. ]]; then
                echo "$ip"
                return
            fi
        done
        
        # Fallback: find interface with "status: active" that has inet
        ip=$(ifconfig 2>/dev/null | grep -B5 'status: active' | grep 'inet ' | grep -v '127.0.0.1' | grep -v '192.168.64' | head -1 | awk '{print $2}')
        if [ -n "$ip" ]; then
            echo "$ip"
            return
        fi
    else
        # Linux
        ip=$(hostname -I 2>/dev/null | awk '{print $1}')
        if [ -n "$ip" ]; then
            echo "$ip"
            return
        fi
        ip=$(ip route get 1 2>/dev/null | awk '{print $7;exit}')
        if [ -n "$ip" ]; then
            echo "$ip"
            return
        fi
    fi
}

LOCAL_IP=$(get_local_ip)

if [ -z "$LOCAL_IP" ]; then
    printf "${RED}Could not determine local IP address${NC}\n"
    echo "Please find your IP manually (ifconfig or ipconfig) and use:"
    echo "  kubectl port-forward --address 0.0.0.0 -n rtc svc/nginx-gateway-service 8080:80"
    exit 1
fi

printf "${GREEN}Local IP Address: ${LOCAL_IP}${NC}\n"
echo ""

# Setup kubectl
if command -v kubectl &> /dev/null; then
    KUBECTL="kubectl"
elif command -v minikube &> /dev/null; then
    KUBECTL="minikube kubectl --"
else
    printf "${RED}kubectl not found${NC}\n"
    exit 1
fi

# Check if minikube is running
if ! minikube status &> /dev/null; then
    printf "${RED}Minikube is not running!${NC}\n"
    echo "Start it with: ./deploy.sh"
    exit 1
fi

# Check if nginx-gateway-service exists
if ! $KUBECTL get svc nginx-gateway-service -n rtc &> /dev/null; then
    printf "${RED}nginx-gateway-service not found in rtc namespace${NC}\n"
    echo "Run ./deploy.sh first to deploy the application"
    exit 1
fi

# Kill ALL existing port-forwards for our services
echo "Stopping existing port-forwards..."
pkill -f "port-forward.*nginx-gateway" 2>/dev/null || true
pkill -f "port-forward.*prometheus-service" 2>/dev/null || true
pkill -f "port-forward.*grafana-service" 2>/dev/null || true
pkill -f "port-forward.*8080:80" 2>/dev/null || true
pkill -f "port-forward.*9090:9090" 2>/dev/null || true
pkill -f "port-forward.*3000:3000" 2>/dev/null || true
sleep 2

echo ""
printf "${GREEN}Starting port-forwards...${NC}\n"
echo ""

# 1. Nginx Gateway - exposed to network (0.0.0.0) for remote load tests
echo "Starting Nginx gateway on 0.0.0.0:8080 (network accessible)..."
nohup $KUBECTL port-forward --address 0.0.0.0 -n rtc svc/nginx-gateway-service 8080:80 > /tmp/nginx-forward.log 2>&1 &
NGINX_PID=$!
sleep 2

# 2. Prometheus - localhost only (for local monitoring)
echo "Starting Prometheus on localhost:9090..."
nohup $KUBECTL port-forward -n rtc svc/prometheus-service 9090:9090 > /tmp/prometheus-forward.log 2>&1 &
PROMETHEUS_PID=$!
sleep 1

# 3. Grafana - localhost only (for local monitoring)
echo "Starting Grafana on localhost:3000..."
nohup $KUBECTL port-forward -n rtc svc/grafana-service 3000:3000 > /tmp/grafana-forward.log 2>&1 &
GRAFANA_PID=$!
sleep 1

echo ""

# Verify port-forwards are running
echo "Verifying port-forwards..."

verify_forward() {
    local name=$1
    local pid=$2
    local url=$3
    
    if ps -p $pid > /dev/null 2>&1; then
        if curl -s "$url" > /dev/null 2>&1; then
            printf "  ${GREEN}✓${NC} $name (PID: $pid)\n"
            return 0
        else
            printf "  ${YELLOW}⚠${NC} $name started but not responding yet (PID: $pid)\n"
            return 0
        fi
    else
        printf "  ${RED}✗${NC} $name failed to start\n"
        return 1
    fi
}

verify_forward "Nginx (localhost:8080)" $NGINX_PID "http://localhost:8080/healthz"
verify_forward "Nginx (${LOCAL_IP}:8080)" $NGINX_PID "http://${LOCAL_IP}:8080/healthz"
verify_forward "Prometheus (localhost:9090)" $PROMETHEUS_PID "http://localhost:9090/-/healthy"
verify_forward "Grafana (localhost:3000)" $GRAFANA_PID "http://localhost:3000/api/health"

echo ""
printf "${GREEN}========================================${NC}\n"
printf "${GREEN}  All Services Ready!                  ${NC}\n"
printf "${GREEN}========================================${NC}\n"
echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  LOCAL ACCESS (your PC):                                       ║"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║  Nginx/API:    http://localhost:8080                           ║"
echo "║  Prometheus:   http://localhost:9090                           ║"
echo "║  Grafana:      http://localhost:3000  (admin/admin)            ║"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║  NETWORK ACCESS (other PCs):                                   ║"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║  HTTP API:     http://${LOCAL_IP}:8080/api/v1/resolve          ║"
echo "║  WebSocket:    ws://${LOCAL_IP}:8080/ws/{nodeId}/connect       ║"
echo "║  Health:       http://${LOCAL_IP}:8080/healthz                 ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Test from another PC:"
echo "  curl 'http://${LOCAL_IP}:8080/api/v1/resolve?clientId=test'"
echo ""
echo "Run Gatling load tests from another PC:"
echo "  ./run-tests.sh -h ${LOCAL_IP} -c 1000 -n 3"
echo ""
printf "${YELLOW}Important:${NC}\n"
echo "  - Firewall: Allow incoming connections on port 8080"
echo "  - macOS: System Preferences > Security > Firewall > Allow"
echo "  - Windows: Add inbound firewall rule for port 8080"
echo ""
echo "Stop all port-forwards:"
echo "  pkill -f 'port-forward'"
echo ""
echo "Or stop individually:"
echo "  kill $NGINX_PID      # Nginx"
echo "  kill $PROMETHEUS_PID # Prometheus"
echo "  kill $GRAFANA_PID    # Grafana"
echo ""
echo "View logs:"
echo "  tail -f /tmp/nginx-forward.log"
echo "  tail -f /tmp/prometheus-forward.log"
echo "  tail -f /tmp/grafana-forward.log"
echo ""

