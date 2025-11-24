# Prometheus Query API - Quick Start

Query Prometheus metrics through your Load-Balancer API in Kubernetes.

## 🚀 Quick Setup

### 1. Configure Load-Balancer

**Docker Compose:**
```yaml
load-balancer:
  environment:
    PROMETHEUS_HOST: prometheus
    PROMETHEUS_PORT: 9090
```

**Kubernetes:**
```yaml
env:
- name: PROMETHEUS_HOST
  value: "prometheus-service"  # or "prometheus-service.monitoring.svc.cluster.local"
- name: PROMETHEUS_PORT
  value: "9090"
```

### 2. Test Connection

```bash
# Local (Docker Compose)
curl http://localhost:8081/api/v1/query/metrics-summary | jq

# Kubernetes
kubectl run -it --rm test --image=curlimages/curl --restart=Never -- \
  curl http://load-balancer-service.reactive-rtc:8081/api/v1/query/metrics-summary
```

## 📊 API Endpoints

### Get All Metrics (Recommended)

```bash
curl http://load-balancer:8081/api/v1/query/metrics-summary
```

**Response:**
```json
{
  "totalThroughput": 12500.5,        # messages/sec across all nodes
  "p95LatencySeconds": 0.245,         # p95 latency in seconds
  "p95LatencyMs": 245.0,              # p95 latency in milliseconds
  "totalConnections": 8500,           # active WebSocket connections
  "totalConsumerLag": 120,            # Kafka consumer lag
  "nodeCount": 3,                     # number of socket nodes
  "throughputPerNode": 4166.83,       # avg throughput per node
  "connectionsPerNode": 2833.33,      # avg connections per node
  "lagPerNode": 40.0                  # avg lag per node
}
```

### Throughput by Node

```bash
curl http://load-balancer:8081/api/v1/query/throughput-by-node
```

**Response:**
```json
{
  "socket-node-1": 4200.5,
  "socket-node-2": 4150.2,
  "socket-node-3": 4150.8
}
```

### Kafka Lag by Node

```bash
curl http://load-balancer:8081/api/v1/query/kafka-lag-by-node
```

**Response:**
```json
{
  "socket-delivery-socket-node-1": 45.0,
  "socket-delivery-socket-node-2": 38.0,
  "socket-delivery-socket-node-3": 37.0
}
```

### Custom PromQL Query

```bash
# URL encode your query
curl "http://load-balancer:8081/api/v1/query/promql?query=sum(rtc_socket_active_connections)"

# With jq for pretty output
curl -s "http://load-balancer:8081/api/v1/query/promql?query=sum(rtc_socket_active_connections)" | jq '.value'
```

## 🎯 Common Use Cases

### Check if Scaling Needed

```bash
#!/bin/bash
METRICS=$(curl -s http://load-balancer:8081/api/v1/query/metrics-summary)

P95_MS=$(echo $METRICS | jq -r '.p95LatencyMs')
LAG=$(echo $METRICS | jq -r '.totalConsumerLag')
NODES=$(echo $METRICS | jq -r '.nodeCount')

echo "p95 Latency: ${P95_MS}ms"
echo "Kafka Lag: ${LAG} messages"
echo "Nodes: ${NODES}"

if (( $(echo "$P95_MS > 500" | bc -l) )); then
  echo "⚠️  Scale out: Latency exceeds 500ms SLO"
fi

if [ "$LAG" -gt 1000 ]; then
  echo "⚠️  Scale out: Kafka lag too high"
fi
```

### Monitor Node Health

```bash
# Get throughput and lag for each node
THROUGHPUT=$(curl -s http://load-balancer:8081/api/v1/query/throughput-by-node)
LAG=$(curl -s http://load-balancer:8081/api/v1/query/kafka-lag-by-node)

echo "Throughput by node:"
echo $THROUGHPUT | jq

echo "Lag by node:"
echo $LAG | jq
```

### Python Monitoring Script

```python
import requests

def get_metrics():
    url = "http://load-balancer:8081/api/v1/query/metrics-summary"
    response = requests.get(url)
    return response.json()

metrics = get_metrics()

print(f"Throughput: {metrics['totalThroughput']:.2f} msg/s")
print(f"p95 Latency: {metrics['p95LatencyMs']:.2f}ms")
print(f"Connections: {metrics['totalConnections']}")
print(f"Kafka Lag: {metrics['totalConsumerLag']}")
print(f"Nodes: {metrics['nodeCount']}")

# Check health
if metrics['p95LatencyMs'] > 500:
    print("❌ Latency SLO violated!")
if metrics['totalConsumerLag'] > 1000:
    print("❌ High Kafka lag!")
```

## ☸️ Kubernetes Examples

### Port Forward Load-Balancer

```bash
kubectl port-forward -n reactive-rtc svc/load-balancer-service 8081:8081

# Then query from local machine
curl http://localhost:8081/api/v1/query/metrics-summary | jq
```

### Query from Pod

```bash
kubectl run -it --rm query --image=curlimages/curl --restart=Never -n reactive-rtc -- \
  curl http://load-balancer-service:8081/api/v1/query/metrics-summary
```

### Autoscaling CronJob

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: autoscaler
  namespace: reactive-rtc
spec:
  schedule: "*/2 * * * *"
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: scaler
            image: curlimages/curl:latest
            command:
            - /bin/sh
            - -c
            - |
              METRICS=$(curl -s http://load-balancer-service:8081/api/v1/query/metrics-summary)
              P95=$(echo $METRICS | grep -o '"p95LatencyMs":[0-9.]*' | cut -d: -f2)
              
              if (( $(echo "$P95 > 500" | bc -l) )); then
                echo "Scaling out due to high latency: ${P95}ms"
                kubectl scale deployment socket-deployment --replicas=5 -n reactive-rtc
              fi
          restartPolicy: OnFailure
          serviceAccountName: autoscaler-sa
```

## 🔧 Troubleshooting

### Check Prometheus Connection

```bash
# Test from load-balancer pod
kubectl exec -it deployment/load-balancer -n reactive-rtc -- sh

# Inside pod:
wget -O- http://prometheus-service:9090/-/healthy
# or
wget -O- http://prometheus-service.monitoring.svc.cluster.local:9090/-/healthy
```

### Verify Metrics

```bash
# Check if socket nodes are exposing metrics
kubectl exec -it deployment/socket-deployment -n reactive-rtc -- \
  wget -O- http://localhost:8080/metrics | grep rtc_socket

# Check if Prometheus is scraping
curl "http://prometheus:9090/api/v1/targets" | jq '.data.activeTargets[] | select(.labels.job=="socket-nodes")'
```

### Debug Empty Metrics

```bash
# Test query directly in Prometheus
curl "http://prometheus:9090/api/v1/query?query=rtc_socket_active_connections"

# Check Prometheus logs
kubectl logs -f deployment/prometheus -n monitoring
```

## 📚 More Resources

- **Full Guide**: [PROMETHEUS_QUERY_API_GUIDE.md](./PROMETHEUS_QUERY_API_GUIDE.md)
- **Kafka Metrics**: [KAFKA_MONITORING_GUIDE.md](./KAFKA_MONITORING_GUIDE.md)
- **App Metrics**: [METRICS_RECORDING_SUMMARY.md](./METRICS_RECORDING_SUMMARY.md)

## ✅ Quick Health Check

```bash
curl -s http://load-balancer:8081/api/v1/query/metrics-summary | jq '{
  healthy: (
    (.p95LatencyMs < 500) and 
    (.totalConsumerLag < 1000) and 
    (.nodeCount > 0)
  ),
  p95_ms: .p95LatencyMs,
  lag: .totalConsumerLag,
  nodes: .nodeCount
}'
```

Expected healthy output:
```json
{
  "healthy": true,
  "p95_ms": 245.5,
  "lag": 120,
  "nodes": 3
}
```


