#!/bin/bash

# Reactive RTC - Minikube Local Deployment Script
# Simplified for local development on minikube

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

NAMESPACE="rtc"
TAG="${TAG:-latest}"
REGISTRY="local"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Reactive RTC - Minikube Deployment   ${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

print_section() { echo -e "\n${GREEN}>>> $1${NC}"; }
print_warning() { echo -e "${YELLOW}⚠ $1${NC}"; }
print_error() { echo -e "${RED}✗ $1${NC}"; }
print_success() { echo -e "${GREEN}✓ $1${NC}"; }

# Setup kubectl
if command -v kubectl &> /dev/null; then
    KUBECTL="kubectl"
elif command -v minikube &> /dev/null; then
    KUBECTL="minikube kubectl --"
    $KUBECTL version --client &> /dev/null
else
    print_error "kubectl not found. Run: brew install kubectl"
    exit 1
fi

# Check minikube is running
print_section "Checking Minikube"
if ! minikube status &> /dev/null; then
    print_error "Minikube is not running!"
    echo "Start it with: minikube start --driver=docker --cpus=4 --memory=8192"
    exit 1
fi
print_success "Minikube is running"

# Enable minikube addons
print_section "Enabling Minikube Addons"
minikube addons enable storage-provisioner 2>/dev/null || true
minikube addons enable default-storageclass 2>/dev/null || true
minikube addons enable metrics-server 2>/dev/null || true
print_success "Addons enabled"

# Create namespace
print_section "Creating Namespace"
$KUBECTL create namespace ${NAMESPACE} --dry-run=client -o yaml | $KUBECTL apply -f -
print_success "Namespace ${NAMESPACE} ready"

# Deploy Strimzi Kafka (open source, no licensing issues)
print_section "Deploying Kafka (Strimzi)"

# Check if Strimzi operator is installed
if ! $KUBECTL get deployment strimzi-cluster-operator -n ${NAMESPACE} &> /dev/null; then
    echo "Installing Strimzi Kafka operator..."
    $KUBECTL create -f 'https://strimzi.io/install/latest?namespace=rtc' -n ${NAMESPACE} 2>/dev/null || true
    
    echo "Waiting for Strimzi operator..."
    sleep 10
    $KUBECTL wait --for=condition=ready pod -l name=strimzi-cluster-operator -n ${NAMESPACE} --timeout=180s || true
    print_success "Strimzi operator installed"
else
    print_success "Strimzi operator already installed"
fi

# Create Kafka cluster (KRaft mode - no ZooKeeper, Kafka 4.x)
if ! $KUBECTL get kafka kafka -n ${NAMESPACE} &> /dev/null; then
    echo "Creating Kafka cluster (KRaft mode)..."
    cat <<EOF | $KUBECTL apply -n ${NAMESPACE} -f -
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaNodePool
metadata:
  name: combined
  labels:
    strimzi.io/cluster: kafka
spec:
  replicas: 1
  roles:
    - controller
    - broker
  storage:
    type: ephemeral
---
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: kafka
  annotations:
    strimzi.io/node-pools: enabled
    strimzi.io/kraft: enabled
spec:
  kafka:
    version: 4.0.0
    metadataVersion: "4.0"
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
    config:
      offsets.topic.replication.factor: 1
      transaction.state.log.replication.factor: 1
      transaction.state.log.min.isr: 1
      default.replication.factor: 1
      min.insync.replicas: 1
  entityOperator:
    topicOperator: {}
    userOperator: {}
EOF
    
    echo "Waiting for Kafka cluster to be ready (this may take 2-3 minutes)..."
    sleep 30
    $KUBECTL wait kafka/kafka --for=condition=Ready -n ${NAMESPACE} --timeout=300s || {
        print_warning "Kafka not ready yet, continuing anyway..."
    }
    print_success "Kafka cluster created"
else
    print_success "Kafka cluster already exists"
fi

# Deploy Redis
print_section "Deploying Redis"

if ! $KUBECTL get deployment redis -n ${NAMESPACE} &> /dev/null; then
    echo "Deploying Redis..."
    cat <<EOF | $KUBECTL apply -n ${NAMESPACE} -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  labels:
    app: redis
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          ports:
            - containerPort: 6379
          resources:
            requests:
              memory: 64Mi
              cpu: 50m
            limits:
              memory: 128Mi
              cpu: 100m
---
apiVersion: v1
kind: Service
metadata:
  name: redis-master
  labels:
    app: redis
spec:
  ports:
    - port: 6379
      targetPort: 6379
  selector:
    app: redis
EOF
    print_success "Redis deployed"
else
    print_success "Redis already deployed"
fi

# Wait for Redis
echo "Waiting for Redis..."
$KUBECTL wait --for=condition=ready pod -l app=redis -n ${NAMESPACE} --timeout=60s || true

# Build images in minikube's Docker
print_section "Building Docker Images"
echo "Switching to minikube's Docker daemon..."
eval $(minikube docker-env)

cd ../..  # Go to project root

LB_IMAGE="${REGISTRY}/reactive-rtc-load-balancer:${TAG}"
SOCKET_IMAGE="${REGISTRY}/reactive-rtc-socket:${TAG}"

echo "Building load-balancer..."
docker build -f load-balancer/Dockerfile -t ${LB_IMAGE} .

echo "Building socket..."
docker build -f socket/Dockerfile -t ${SOCKET_IMAGE} .

print_success "Images built in minikube"
cd deploy/k8s

# Update YAML files for local images
print_section "Updating Configurations"

# Update image names and set imagePullPolicy: Never
sed -i.bak "s|image: .*/reactive-rtc-load-balancer:.*|image: ${LB_IMAGE}|g" 03-load-balancer.yaml
sed -i.bak "s|image: .*/reactive-rtc-socket:.*|image: ${SOCKET_IMAGE}|g" 04-socket.yaml
sed -i.bak "s|image: your-registry/reactive-rtc-load-balancer:latest|image: ${LB_IMAGE}|g" 03-load-balancer.yaml
sed -i.bak "s|image: your-registry/reactive-rtc-socket:latest|image: ${SOCKET_IMAGE}|g" 04-socket.yaml
sed -i.bak "s|imagePullPolicy: IfNotPresent|imagePullPolicy: Never|g" 03-load-balancer.yaml
sed -i.bak "s|imagePullPolicy: IfNotPresent|imagePullPolicy: Never|g" 04-socket.yaml
sed -i.bak "s|imagePullPolicy: Always|imagePullPolicy: Never|g" 03-load-balancer.yaml
sed -i.bak "s|imagePullPolicy: Always|imagePullPolicy: Never|g" 04-socket.yaml
rm -f *.bak

print_success "Configurations updated"

# Deploy application
print_section "Deploying Application"

$KUBECTL apply -f 01-rbac.yaml
print_success "RBAC applied"

$KUBECTL apply -f 02-config.yaml
print_success "Config applied"

$KUBECTL apply -f 03-load-balancer.yaml
print_success "Load-balancer deployed"

$KUBECTL apply -f 04-socket.yaml
print_success "Socket nodes deployed"

$KUBECTL apply -f 06-nginx-gateway.yaml
print_success "Nginx gateway deployed"

$KUBECTL apply -f 07-prometheus.yaml
print_success "Prometheus deployed"

$KUBECTL apply -f 08-monitoring.yaml
print_success "Grafana & Kafka Exporter deployed"

# Wait for pods
print_section "Waiting for Pods"

echo "Waiting for load-balancer..."
$KUBECTL wait --for=condition=ready pod -l app=load-balancer -n ${NAMESPACE} --timeout=120s || true

echo "Waiting for socket nodes..."
$KUBECTL wait --for=condition=ready pod -l app=socket -n ${NAMESPACE} --timeout=120s || true

echo "Waiting for nginx..."
$KUBECTL wait --for=condition=ready pod -l app=nginx-gateway -n ${NAMESPACE} --timeout=120s || true

# Status
print_section "Deployment Status"
echo ""
$KUBECTL get pods -n ${NAMESPACE}

# Start port-forwarding
print_section "Starting Port Forwarding"

# Kill any existing port-forwards
pkill -f "port-forward.*nginx-gateway" 2>/dev/null || true
pkill -f "port-forward.*prometheus" 2>/dev/null || true
pkill -f "port-forward.*grafana" 2>/dev/null || true
sleep 2

# Start nginx gateway port-forward in background (using nohup to survive script exit)
echo "Starting nginx gateway on localhost:8080..."
nohup $KUBECTL port-forward -n ${NAMESPACE} svc/nginx-gateway-service 8080:80 >/dev/null 2>&1 &
sleep 2

# Verify nginx port-forward is running
if curl -s http://localhost:8080/healthz >/dev/null 2>&1; then
    print_success "Nginx gateway port-forward started (localhost:8080)"
else
    print_warning "Nginx gateway port-forward may not be ready yet"
fi

# Start prometheus port-forward in background
echo "Starting prometheus on localhost:9090..."
nohup $KUBECTL port-forward -n ${NAMESPACE} svc/prometheus-service 9090:9090 >/dev/null 2>&1 &
sleep 2

# Verify prometheus port-forward is running
if curl -s http://localhost:9090/-/healthy >/dev/null 2>&1; then
    print_success "Prometheus port-forward started (localhost:9090)"
else
    print_warning "Prometheus port-forward may not be ready yet"
fi

# Start grafana port-forward in background
echo "Starting grafana on localhost:3000..."
nohup $KUBECTL port-forward -n ${NAMESPACE} svc/grafana-service 3000:3000 >/dev/null 2>&1 &
sleep 2

# Verify grafana port-forward is running
if curl -s http://localhost:3000/api/health >/dev/null 2>&1; then
    print_success "Grafana port-forward started (localhost:3000)"
else
    print_warning "Grafana port-forward may not be ready yet"
fi

# Test the API endpoint
print_section "Testing API"
echo ""
RESPONSE=$(curl -s 'http://localhost:8080/api/v1/resolve?clientId=test-user' 2>/dev/null || echo "FAILED")
if [[ "$RESPONSE" == *"nodeId"* ]]; then
    print_success "API is working!"
    echo "Response: $RESPONSE"
else
    print_warning "API test failed. Response: $RESPONSE"
    echo "Try manually: curl 'http://localhost:8080/api/v1/resolve?clientId=test'"
fi

# Access info
print_section "Access Information"

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  Services are now accessible on localhost:                     ║"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║  Nginx Gateway:  http://localhost:8080                         ║"
echo "║  Prometheus:     http://localhost:9090                         ║"
echo "║  Grafana:        http://localhost:3000  (admin/admin)          ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Quick test commands (note: quote URLs in zsh!):"
echo ""
echo "  # Get assigned socket node:"
echo "  curl 'http://localhost:8080/api/v1/resolve?clientId=myuser'"
echo ""
echo "  # Health check:"
echo "  curl http://localhost:8080/healthz"
echo ""
echo "  # Connect via WebSocket (replace socket-xxx with actual nodeId):"
echo "  wscat -c 'ws://localhost:8080/ws/socket-xxx/connect?clientId=myuser'"
echo ""
echo "View logs:"
echo "  $KUBECTL logs -n ${NAMESPACE} -l app=load-balancer -f"
echo "  $KUBECTL logs -n ${NAMESPACE} -l app=socket -f"
echo ""
echo "Stop all port-forwarding:"
echo "  pkill -f 'port-forward'"
echo ""
echo "Or stop individually:"
echo "  pkill -f 'port-forward.*nginx-gateway'"
echo "  pkill -f 'port-forward.*prometheus'"
echo "  pkill -f 'port-forward.*grafana'"
echo ""
echo "Restart port-forwarding manually if needed:"
echo "  $KUBECTL port-forward -n rtc svc/nginx-gateway-service 8080:80 &"
echo "  $KUBECTL port-forward -n rtc svc/prometheus-service 9090:9090 &"
echo "  $KUBECTL port-forward -n rtc svc/grafana-service 3000:3000 &"
echo ""

print_success "Deployment complete! Port-forwarding is running in background (using nohup)."
