# Kubernetes Deployment Guide - Reactive RTC

```
minikube start --driver=docker --cpus=10 --memory=15900
minikube kubectl -- logs -n rtc -l app=load-balancer --tail=5000000
minikube dashboard
minikube kubectl -- get pods -n rtc
minikube delete
```
This guide provides comprehensive instructions for deploying the Reactive RTC WebSocket system to Kubernetes with full autoscaling, leader election, and monitoring capabilities.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Quick Start](#quick-start)
4. [Component Details](#component-details)
5. [Configuration](#configuration)
6. [Scaling Strategy](#scaling-strategy)
7. [Monitoring & Observability](#monitoring--observability)
8. [Troubleshooting](#troubleshooting)
9. [Production Considerations](#production-considerations)

---

## Architecture Overview

### Components

```
┌─────────────────────────────────────────────────────────────────┐
│                         Kubernetes Cluster                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────┐                                           │
│  │  Nginx Gateway   │  ← External Load Balancer (Port 80)      │
│  │  (HPA: 2-10)     │  ← Routes WS to specific pods            │
│  └────────┬─────────┘                                           │
│           │                                                       │
│           ├──────────────┬──────────────────────┐               │
│           │              │                       │               │
│  ┌────────▼────┐  ┌─────▼────────┐   ┌────────▼────────┐     │
│  │Load-Balancer│  │ Socket Node 0│   │  Socket Node N  │     │
│  │(Deploy+HPA) │  │ (Deployment) │...│  (Deployment)   │     │
│  │ 1-5 replicas│  │  LB Scaled   │   │   LB Scaled     │     │
│  │             │  │   2-50 pods  │   │    2-50 pods    │     │
│  │ Master Node │  └──────┬───────┘   └─────────┬───────┘     │
│  │ via Lease   │         │                     │               │
│  └─────┬───────┘         │                     │               │
│        │                 │                     │               │
│        │        ┌────────▼─────────────────────▼─────┐        │
│        │        │         Kafka Cluster              │        │
│        │        │ (Message Relay & Control Signals)  │        │
│        │        └────────────────┬───────────────────┘        │
│        │                         │                             │
│        │        ┌────────────────▼───────────────────┐        │
│        │        │         Redis Cluster              │        │
│        │        │ (Session State & Heartbeats)       │        │
│        │        └────────────────────────────────────┘        │
│        │                                                        │
│        │        ┌────────────────────────────────────┐        │
│        └───────►│      Prometheus + Grafana          │        │
│                 │   (Metrics & Service Discovery)     │        │
│                 └────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────────────┘
```

### Key Features

- **Leader Election**: Load-balancer pods use Kubernetes Lease API for leader election (15s lease)
- **Autoscaling**: 
  - Load-balancer: HPA based on CPU/Memory (1-5 replicas)
  - Socket nodes: KEDA based on Kafka lag, CPU, Memory, connections, latency (2-50 replicas)
  - Nginx: HPA based on CPU/Memory (2-10 replicas)
- **Graceful Shutdown**: Socket nodes drain connections over 5 minutes before termination
- **Pod Deletion Cost**: Master node updates deletion costs to remove least-loaded pods first
- **Service Discovery**: Prometheus automatically discovers all pods
- **Path-Based Routing**: Nginx routes `/ws/{podId}/connect` to specific socket pods

---

## Prerequisites

### Required Tools

```bash
# Kubernetes cluster (v1.24+)
kubectl version --client

# Helm (v3.x)
helm version

# Docker (for building images)
docker version
```

### Required Kubernetes Features

- **RBAC enabled** (for ServiceAccounts and leader election)
- **Metrics Server** (for HPA)
- **StorageClass** (for Prometheus/Grafana persistence - optional)

### Install KEDA (Kubernetes Event Driven Autoscaler)

```bash
# Install KEDA operator
kubectl apply -f https://github.com/kedacore/keda/releases/download/v2.12.0/keda-2.12.0.yaml

# Verify installation
kubectl get pods -n keda
```

---

## Quick Start

### 1. Build and Push Docker Images

```bash
# Build load-balancer image
docker build -f load-balancer/Dockerfile -t your-registry/reactive-rtc-load-balancer:latest .
docker push your-registry/reactive-rtc-load-balancer:latest

# Build socket image
docker build -f socket/Dockerfile -t your-registry/reactive-rtc-socket:latest .
docker push your-registry/reactive-rtc-socket:latest
```

### 2. Update Image References

Edit the deployment files to reference your registry:

```bash
# In deploy/k8s/03-load-balancer.yaml
image: your-registry/reactive-rtc-load-balancer:latest

# In deploy/k8s/04-socket.yaml
image: your-registry/reactive-rtc-socket:latest
```

### 3. Deploy Infrastructure (Kafka, Redis, Prometheus)

```bash
# Option 1: Using Helm (recommended)
# Add Bitnami repo
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# Install Kafka
helm install kafka bitnami/kafka \
  --namespace rtc \
  --create-namespace \
  --set replicaCount=3 \
  --set defaultReplicationFactor=3 \
  --set offsetsTopicReplicationFactor=3

# Install Redis
helm install redis bitnami/redis \
  --namespace rtc \
  --set architecture=standalone \
  --set auth.enabled=false

# Option 2: Using provided manifests
kubectl apply -f deploy/k8s/infrastructure/
```

### 4. Deploy Application

```bash
# Apply manifests in order
kubectl apply -f deploy/k8s/01-rbac.yaml
kubectl apply -f deploy/k8s/02-config.yaml
kubectl apply -f deploy/k8s/03-load-balancer.yaml
kubectl apply -f deploy/k8s/04-socket.yaml
kubectl apply -f deploy/k8s/05-keda-scaler.yaml
kubectl apply -f deploy/k8s/06-nginx-gateway.yaml
kubectl apply -f deploy/k8s/07-prometheus.yaml
```

### 5. Verify Deployment

```bash
# Check all pods are running
kubectl get pods -n rtc

# Check services
kubectl get svc -n rtc

# Check HPA status
kubectl get hpa -n rtc

# Check KEDA scaler
kubectl get scaledobject -n rtc

# View logs
kubectl logs -n rtc -l app=load-balancer --tail=50 -f
kubectl logs -n rtc -l app=socket --tail=50 -f
```

### 6. Get External IP

```bash
# Get nginx gateway external IP
kubectl get svc nginx-gateway-service -n rtc

# Example output:
# NAME                    TYPE           CLUSTER-IP      EXTERNAL-IP      PORT(S)        AGE
# nginx-gateway-service   LoadBalancer   10.100.200.50   35.123.456.789   80:30080/TCP   5m
```

### 7. Test Connection

```bash
# Step 1: Get socket assignment from load-balancer
curl http://<EXTERNAL-IP>/api/v1/resolve?clientId=test-client

# Response: {"socketNodeId": "socket-0", "url": "/ws/socket-0/connect"}

# Step 2: Connect to assigned socket
wscat -c ws://<EXTERNAL-IP>/ws/socket-0/connect?clientId=test-client
```

---

## Component Details

### 1. Load-Balancer

**Purpose**: Assigns clients to socket nodes using consistent hashing with weights

**Scaling**: HPA with 1-5 replicas based on CPU/Memory (60% threshold)

**Leader Election**:
- Uses Kubernetes Lease API
- Lease duration: 15 seconds
- Only leader processes heartbeats and sends scale decisions
- Automatic failover if leader dies

**Responsibilities**:
- Process socket node heartbeats from Redis
- Recompute hash ring weights based on load
- Publish ring updates to Kafka
- Send scale-out/scale-in signals to KEDA
- Update pod deletion costs before scale-in

**Endpoints**:
- `GET /api/v1/resolve?clientId=<id>` - Get socket node assignment (returns `{"nodeId":"socket-xxx"}`)
- `GET /healthz` - Health check
- `GET /metrics` - Prometheus metrics

### 2. Socket Nodes

**Purpose**: Handle WebSocket connections and message relay

**Scaling**: Load-balancer directly scales the socket Deployment (2-50 replicas) based on:
- Kafka consumer lag
- CPU utilization (70%)
- Memory utilization (75%)
- Active connections (15,000 threshold)
- P95 latency (500ms threshold)

**Deployment Benefits**:
- Pod deletion costs work (least-loaded pods removed first)
- Stable DNS via subdomain: `{podname}.socket.rtc.svc.cluster.local`
- Optimal scale-down behavior

**Graceful Shutdown (Drain Process)**:
1. Kubernetes calls `/drain` endpoint (preStop hook)
2. Socket node enters draining mode (rejects new connections)
3. Periodically disconnects connections in batches over 5 minutes
4. After drain complete, Kubernetes terminates pod

**Endpoints**:
- `GET /ws/{nodeId}/connect?clientId=<id>&resumeOffset=<n>` - WebSocket upgrade to specific socket node
- `POST /drain` - Start drain process
- `GET /drain/status` - Drain status
- `GET /healthz` - Health check (fails during drain)
- `GET /readyz` - Readiness check
- `GET /metrics` - Prometheus metrics

### 3. Nginx Gateway

**Purpose**: External load balancer and WebSocket proxy

**Scaling**: HPA with 2-10 replicas based on CPU/Memory (70% threshold)

**Routing**:
- `/api/v1/resolve` → Load-balancer (round-robin) - returns assigned socket node
- `/ws/{nodeId}/connect` → Specific socket node via nginx rewrite
- `/ws/{podId}/connect` → Specific socket pod

**Configuration**:
- Optimized for millions of WebSocket connections
- Worker connections: 100,000 per worker
- Worker processes: auto (based on CPU cores)
- Connection timeouts: 3600s (1 hour)

### 4. Prometheus

**Purpose**: Metrics collection and service discovery

**Service Discovery**:
- Automatically discovers all pods with `prometheus.io/scrape: "true"` annotation
- Dynamically adds/removes targets as pods scale
- Scrapes metrics from:
  - Load-balancer pods
  - Socket node pods
  - Nginx gateway pods
  - Kubernetes API server
  - Kubelets

**Retention**: 30 days

---

## Configuration

### Environment Variables

All configuration is stored in `deploy/k8s/02-config.yaml`.

**Critical Settings**:

```yaml
# Kubernetes
KUBERNETES_NAMESPACE: "rtc"
ENABLE_LEADER_ELECTION: "true"

# Infrastructure
KAFKA_BOOTSTRAP: "kafka-service.rtc.svc.cluster.local:9092"
REDIS_URL: "redis://redis-service.rtc.svc.cluster.local:6379"

# Autoscaling Parameters
CONN_PER_POD: "5000"    # Target connections per socket node
MPS_PER_POD: "2500"      # Target messages/sec per socket node
L_SLO_MS: "500.0"        # Latency SLO in milliseconds

# Ring Secret (MUST change in production)
RING_SECRET: "change-this-to-a-secure-random-value-in-production"
```

### Secrets

Update `deploy/k8s/02-config.yaml` with production values:

```bash
# Generate secure ring secret
RING_SECRET=$(openssl rand -base64 32)

# Update secret
kubectl create secret generic rtc-secret \
  --from-literal=RING_SECRET=$RING_SECRET \
  --namespace rtc \
  --dry-run=client -o yaml | kubectl apply -f -
```

---

## Scaling Strategy

### Automatic Scaling

**Socket Nodes (Load-Balancer Direct Scaling)**:

```yaml
Master Load-Balancer analyzes and scales directly:
  - Monitors: CPU, Memory, Connections, MPS, Latency, Kafka Lag
  - Analyzes: Load trends, urgency levels, system health
  - Decides: Scale out (+1 to +5) or Scale in (-1) or No change (0)
  - Updates: Pod deletion costs before scale-in
  - Scales: Calls Kubernetes API to scale Deployment
  - Result: Optimal pod selection (least-loaded terminated first)

Backup KEDA Scaler:
  - Triggers only on extreme scenarios (CPU > 90%, Memory > 90%)
  - Conservative thresholds to avoid conflicts
  - 10-minute cooldown periods
```

**Load-Balancer (HPA)**:

```yaml
Scale Out when:
  - CPU utilization > 60%
  - Memory utilization > 60%

Scale In after:
  - 5 minutes below threshold
```

### Manual Scaling

```bash
# Scale socket nodes
kubectl scale deployment socket --replicas=10 -n rtc

# Scale load-balancer
kubectl scale deployment load-balancer --replicas=3 -n rtc

# Scale nginx
kubectl scale deployment nginx-gateway --replicas=5 -n rtc
```

### Pod Deletion Cost Strategy

Before scale-in, the master load-balancer updates pod deletion costs:
- **Lower cost** = fewer active connections = deleted first
- **Higher cost** = more active connections = deleted last

This ensures minimal disruption during scale-down.

---

## Monitoring & Observability

### Access Prometheus

```bash
# Port-forward to Prometheus
kubectl port-forward -n rtc svc/prometheus-service 9090:9090

# Open http://localhost:9090
```

### Key Metrics

**Socket Nodes**:
```promql
# Active connections per pod
rtc_socket_active_connections{app="socket"}

# P95 latency per pod
rtc_socket_latency{quantile="0.95",app="socket"}

# Message rate per pod
rate(rtc_socket_messages_total{app="socket"}[5m])

# Current replicas
kube_deployment_spec_replicas{deployment="socket"}
```

**Load-Balancer**:
```promql
# Ring nodes
rtc_lb_ring_nodes{app="load-balancer"}

# Leader status (1 = leader, 0 = follower)
rtc_lb_is_leader{app="load-balancer"}
```

**Scaling Decisions**:
```promql
# Scale events
rate(rtc_lb_scale_signals_total{app="load-balancer"}[5m])
```

### Logs

```bash
# Load-balancer logs (leader election)
kubectl logs -n rtc -l app=load-balancer --tail=100 -f | grep -E "ACQUIRED|LOST|MASTER"

# Socket node logs (drain process)
kubectl logs -n rtc -l app=socket --tail=100 -f | grep -i drain

# All errors
kubectl logs -n rtc -l app=socket --tail=100 -f | grep -i error
```

---

## Troubleshooting

### Pods Not Starting

```bash
# Check pod status
kubectl get pods -n rtc

# Describe pod for events
kubectl describe pod <pod-name> -n rtc

# Check logs
kubectl logs <pod-name> -n rtc

# Common issues:
# - Image pull errors: Check image name and registry credentials
# - ConfigMap missing: Apply 02-config.yaml
# - RBAC errors: Apply 01-rbac.yaml
```

### Leader Election Issues

```bash
# Check lease status
kubectl get lease -n rtc load-balancer-leader -o yaml

# Check which pod is leader
kubectl logs -n rtc -l app=load-balancer | grep "ACQUIRED"

# Force new leader election (delete lease)
kubectl delete lease load-balancer-leader -n rtc
```

### Scaling Not Working

```bash
# Check HPA status
kubectl get hpa -n rtc
kubectl describe hpa load-balancer-hpa -n rtc

# Check Deployment status
kubectl get deployment socket -n rtc
kubectl describe deployment socket -n rtc

# Check leader election (only leader scales)
kubectl logs -n rtc -l app=load-balancer | grep -E "ACQUIRED|LOST|MASTER|Scaling Deployment"

# Check KEDA scaler (backup)
kubectl get scaledobject -n rtc
kubectl describe scaledobject socket-scaler-backup -n rtc

# Check metrics server
kubectl top nodes
kubectl top pods -n rtc

# Check Prometheus connectivity
kubectl exec -n rtc <socket-pod> -- curl -s http://prometheus-service:9090/-/healthy
```

### WebSocket Connection Failures

```bash
# Check nginx logs
kubectl logs -n rtc -l app=nginx-gateway

# Check if pod DNS resolves
kubectl exec -n rtc <nginx-pod> -- nslookup socket-0.socket.rtc.svc.cluster.local

# Test socket pod directly
kubectl port-forward -n rtc socket-0 8080:8080
# First get assigned node
curl http://localhost:8080/api/v1/resolve?clientId=test
# Returns: {"nodeId":"socket-xxx-yyy"}

# Then connect to that node
wscat -c "ws://localhost:8080/ws/socket-xxx-yyy/connect?clientId=test"
```

### Drain Not Working

```bash
# Check drain status
kubectl exec -n rtc socket-0 -- curl http://localhost:8080/drain/status

# Manually trigger drain
kubectl exec -n rtc socket-0 -- curl -X POST http://localhost:8080/drain

# Check if preStop hook is configured
kubectl get statefulset socket -n rtc -o yaml | grep -A 10 preStop
```

---

## Production Considerations

### 1. High Availability

```yaml
# Ensure resources:
- At least 3 Kafka brokers
- Redis Sentinel or Redis Cluster
- Multiple availability zones
- PodAntiAffinity rules (already configured)
```

### 2. Resource Limits

Adjust based on load testing:

```yaml
# Socket nodes (per 10k connections)
resources:
  requests:
    cpu: 2000m
    memory: 2Gi
  limits:
    cpu: 4000m
    memory: 4Gi

# Load-balancer
resources:
  requests:
    cpu: 500m
    memory: 512Mi
  limits:
    cpu: 2000m
    memory: 2Gi
```

### 3. Security

```bash
# Update ring secret
kubectl create secret generic rtc-secret \
  --from-literal=RING_SECRET=$(openssl rand -base64 32) \
  --namespace rtc

# Enable network policies
kubectl apply -f deploy/k8s/network-policies.yaml

# Enable TLS for Kafka and Redis
# (Update connection strings in configmap)
```

### 4. Persistence

```yaml
# Add PersistentVolumeClaim for Prometheus
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: prometheus-storage
  namespace: rtc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 100Gi
```

### 5. Backup & Disaster Recovery

```bash
# Backup critical resources
kubectl get configmap,secret -n rtc -o yaml > backup-config.yaml

# Backup Prometheus data (if using PV)
kubectl exec -n rtc prometheus-0 -- tar czf /tmp/prometheus-backup.tar.gz /prometheus
```

### 6. Monitoring Alerts

Set up alerts in Prometheus:

```yaml
# High pod restart rate
alert: HighPodRestartRate
expr: rate(kube_pod_container_status_restarts_total{namespace="rtc"}[15m]) > 0.1

# Socket node drain failures
alert: DrainFailure
expr: rtc_socket_drain_remaining_connections > 0 AND rtc_socket_drain_time_seconds > 330

# Leader election failures
alert: NoLeader
expr: sum(rtc_lb_is_leader) == 0
```

---

## Next Steps

1. **Load Testing**: Use Gatling or similar tool to test with target load
2. **Monitoring**: Set up Grafana dashboards (import from `deploy/grafana-dashboard-complete.json`)
3. **Alerting**: Configure AlertManager for critical alerts
4. **CI/CD**: Integrate deployment into your CI/CD pipeline
5. **Documentation**: Document your specific environment and customizations

---

## Support & Resources

- **GitHub**: [Your repository URL]
- **Kubernetes Docs**: https://kubernetes.io/docs/
- **KEDA Docs**: https://keda.sh/docs/
- **Prometheus Docs**: https://prometheus.io/docs/

---

**Last Updated**: 2025-11-30
**Version**: 1.0
