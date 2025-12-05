#!/bin/bash

# Minikube High Connection Support Setup
# This script configures minikube to allow unsafe sysctls needed for 1M+ WebSocket connections

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Minikube High Connection Setup       ${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Default values - can be overridden with environment variables
CPUS="${CPUS:-10}"
MEMORY="${MEMORY:-15700}"
DRIVER="${DRIVER:-docker}"

echo "Configuration:"
echo "  CPUs: ${CPUS}"
echo "  Memory: ${MEMORY}MB"
echo "  Driver: ${DRIVER}"
echo ""

# Check if minikube is running
if minikube status &> /dev/null; then
    echo -e "${YELLOW}Minikube is currently running.${NC}"
    echo "To enable high connection support, we need to restart minikube."
    echo ""
    read -p "Delete and recreate minikube cluster? (y/N) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborted."
        exit 1
    fi

    echo "Deleting existing minikube cluster..."
    minikube delete
fi

echo ""
echo -e "${GREEN}Starting minikube with high connection support...${NC}"
echo ""

# Start minikube with:
# - Increased resources
# - Unsafe sysctls allowed for socket pods
# - Increased max pods per node
minikube start \
    --driver=${DRIVER} \
    --cpus=${CPUS} \
    --memory=${MEMORY} \
    --extra-config=kubelet.allowed-unsafe-sysctls=net.core.somaxconn,net.ipv4.tcp_tw_reuse \
    --extra-config=kubelet.max-pods=250

echo ""
echo -e "${GREEN}Verifying sysctl configuration...${NC}"

# Verify the configuration
KUBELET_CONFIG=$(minikube ssh "cat /var/lib/kubelet/config.yaml 2>/dev/null | grep -A10 allowedUnsafeSysctls" 2>/dev/null || echo "")

if [[ "$KUBELET_CONFIG" == *"somaxconn"* ]]; then
    echo -e "${GREEN}✓ Unsafe sysctls are configured correctly${NC}"
    echo ""
    echo "Allowed sysctls:"
    echo "$KUBELET_CONFIG"
else
    echo -e "${YELLOW}⚠ Could not verify sysctl configuration${NC}"
    echo "The sysctls may still work - try deploying and check pod status."
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Minikube is ready!                   ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Next steps:"
echo "  1. Run ./deploy.sh to deploy the application"
echo "  2. Socket pods will now support:"
echo "     - net.core.somaxconn=65535 (max listen backlog)"
echo "     - net.ipv4.tcp_tw_reuse=1 (reuse TIME_WAIT sockets)"
echo "     - net.ipv4.ip_local_port_range='1024 65535' (ephemeral ports)"
echo ""
echo "To verify socket pod sysctls after deployment:"
echo "  kubectl exec -n rtc \$(kubectl get pod -n rtc -l app=socket -o name | head -1) -- cat /proc/sys/net/core/somaxconn"
echo ""





