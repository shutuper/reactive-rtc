# Quick Start - Local Minikube Deployment

## Prerequisites

```bash
# Install on macOS (M1/M2 compatible)
brew install minikube kubectl helm
```

Make sure Docker Desktop is installed and running.

## Clean Start (Delete Everything)

If you have a broken deployment, run this first:

```bash
# Delete the entire minikube cluster and start fresh
minikube delete

# Or just delete the rtc namespace
minikube kubectl -- delete namespace rtc
```

## Launch Instructions

### 1. Start Minikube

**Basic setup:**
```bash
minikube start --driver=docker --cpus=4 --memory=8192
```

**High connection setup (1M+ WebSocket connections):**
```bash
# Use the helper script for full configuration
./minikube-high-conn.sh

# Or manually with unsafe sysctls enabled:
minikube start --driver=docker --cpus=10 --memory=15900 \
  --extra-config=kubelet.allowed-unsafe-sysctls=net.core.somaxconn,net.ipv4.tcp_tw_reuse
```

### 2. Run Deploy Script

```bash
cd deploy/k8s
chmod +x deploy.sh
./deploy.sh
```

The script will automatically:
- Enable required minikube addons
- Install Strimzi Kafka operator & create Kafka cluster
- Deploy Redis
- Build Docker images inside minikube
- Deploy all application services

**Note:** First run takes 5-10 minutes (Kafka setup is slow).

### 3. Check Status

```bash
# Watch all pods
minikube kubectl -- get pods -n rtc -w

# Expected pods:
# - kafka-combined-0 (Kafka KRaft broker/controller)
# - kafka-entity-operator-xxx (Strimzi entity operator)
# - redis-xxx (Redis)
# - load-balancer-xxx
# - socket-xxx (2 replicas)
# - nginx-gateway-xxx
# - prometheus-xxx
# - strimzi-cluster-operator-xxx
```

### 4. Access the Application

```bash
# Option 1: Open in browser (creates tunnel)
minikube service nginx-gateway-service -n rtc

# Option 2: Port forward
minikube kubectl -- port-forward -n rtc svc/nginx-gateway-service 8080:80
```

Then test (note: **quote URLs** in zsh!):
```bash
# Get assigned socket node
curl 'http://localhost:8080/api/v1/resolve?clientId=test-client'
# Returns: {"nodeId":"socket-xxx-yyy"}

# Connect via WebSocket (using wscat, replace socket-xxx-yyy with actual nodeId)
wscat -c 'ws://localhost:8080/ws/socket-xxx-yyy/connect?clientId=test-client'
```

### 5. View Logs

```bash
# Load balancer logs
minikube kubectl -- logs -n rtc -l app=load-balancer -f

# Socket node logs
minikube kubectl -- logs -n rtc -l app=socket -f

# Kafka logs
minikube kubectl -- logs -n rtc kafka-combined-0 -f
```

### 6. Access Prometheus

```bash
minikube kubectl -- port-forward -n rtc svc/prometheus-service 9090:9090
# Open http://localhost:9090
```

## Useful Commands

```bash
# Check all pods
minikube kubectl -- get pods -n rtc

# Check services
minikube kubectl -- get svc -n rtc

# Check Kafka status
minikube kubectl -- get kafka -n rtc

# Restart a deployment
minikube kubectl -- rollout restart deployment/load-balancer -n rtc

# Delete everything and start over
minikube delete
minikube start --driver=docker --cpus=4 --memory=8192
./deploy.sh

# Stop minikube (preserves state)
minikube stop

# Resume minikube
minikube start
```

## Troubleshooting

### Pods stuck in Pending
```bash
# Check events
minikube kubectl -- get events -n rtc --sort-by='.lastTimestamp'

# Check pod details
minikube kubectl -- describe pod <pod-name> -n rtc
```

### Kafka not starting
Strimzi Kafka takes 2-3 minutes to start. Check status:
```bash
minikube kubectl -- get kafka -n rtc
minikube kubectl -- get pods -n rtc -l strimzi.io/cluster=kafka
```

### Image pull errors
Images are built inside minikube's Docker. If you see pull errors:
```bash
eval $(minikube docker-env)
docker images | grep reactive-rtc
```

### Out of memory
```bash
minikube stop
minikube start --driver=docker --cpus=4 --memory=8192
```

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Minikube Cluster                     │
├─────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │   Nginx     │  │ Load        │  │  Socket     │     │
│  │   Gateway   │──│ Balancer    │──│  Nodes (2)  │     │
│  └─────────────┘  └─────────────┘  └─────────────┘     │
│         │                │                │             │
│         │         ┌──────┴──────┐        │             │
│         │         │             │        │             │
│  ┌──────▼─────┐  ┌▼───────────┐ ┌▼──────────────┐     │
│  │ Prometheus │  │   Kafka    │ │    Redis      │     │
│  │            │  │  (Strimzi) │ │               │     │
│  └────────────┘  └────────────┘ └───────────────┘     │
└─────────────────────────────────────────────────────────┘
```

## Service Endpoints (Internal)

| Service | Endpoint |
|---------|----------|
| Kafka | `kafka-kafka-bootstrap.rtc.svc.cluster.local:9092` |
| Redis | `redis-master.rtc.svc.cluster.local:6379` |
| Prometheus | `prometheus-service.rtc.svc.cluster.local:9090` |
| Nginx | `nginx-gateway-service.rtc.svc.cluster.local:80` |

## High Connection Configuration (1M+ connections)

For supporting 1M+ WebSocket connections per pod, the following sysctls are configured:

### Pod-level sysctls (in `04-socket.yaml`)
```yaml
securityContext:
  sysctls:
    - name: net.ipv4.ip_local_port_range  # Ephemeral ports
      value: "1024 65535"
    - name: net.core.somaxconn            # Max listen backlog
      value: "65535"
    - name: net.ipv4.tcp_tw_reuse         # Reuse TIME_WAIT sockets
      value: "1"
```

### Kubelet configuration (required for unsafe sysctls)
```bash
# When starting minikube, allow unsafe sysctls:
minikube start \
  --extra-config=kubelet.allowed-unsafe-sysctls=net.core.somaxconn,net.ipv4.tcp_tw_reuse
```

### Verify sysctls in running pod
```bash
# Check if sysctls are applied
kubectl exec -n rtc $(kubectl get pod -n rtc -l app=socket -o name | head -1) -- \
  sh -c 'echo "somaxconn=$(cat /proc/sys/net/core/somaxconn)"; echo "ip_local_port_range=$(cat /proc/sys/net/ipv4/ip_local_port_range)"'
```

### If SysctlForbidden errors occur
```bash
# 1. Delete the minikube cluster
minikube delete

# 2. Restart with unsafe sysctls allowed
./minikube-high-conn.sh

# 3. Redeploy
./deploy.sh
```
