# Prometheus Query API Guide

This guide explains how to query Prometheus metrics through the Load-Balancer API, especially in Kubernetes environments.

## Overview

The Load-Balancer provides a REST API to query Prometheus metrics, making it easy to:
- Get aggregated metrics across all socket nodes
- Query metrics by individual nodes
- Make autoscaling decisions based on real-time metrics
- Avoid direct Prometheus access from external services

## Architecture

```
┌──────────────────┐
│  External Client │
│  (K8s HPA, etc)  │
└────────┬─────────┘
         │ HTTP GET
         ▼
┌──────────────────────────────────┐
│  Load-Balancer                   │
│  ┌────────────────────────────┐  │
│  │ PrometheusQueryService     │  │
│  │ (reactor-netty HttpClient) │  │
│  └────────┬───────────────────┘  │
│           │                       │
└───────────┼───────────────────────┘
            │ Internal HTTP
            ▼
┌──────────────────────┐
│  Prometheus Service  │
│  (metrics storage)   │
└──────────────────────┘
```

## Configuration

### Environment Variables

Add to your load-balancer deployment:

```yaml
env:
  - name: PROMETHEUS_HOST
    value: "prometheus"  # or "prometheus-service.monitoring.svc.cluster.local" in K8s
  - name: PROMETHEUS_PORT
    value: "9090"
```

### Docker Compose (Local)

```yaml
services:
  load-balancer:
    environment:
      PROMETHEUS_HOST: prometheus
      PROMETHEUS_PORT: 9090
    depends_on:
      - prometheus
```

### Kubernetes

For Kubernetes, use the fully qualified domain name (FQDN):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: load-balancer
  namespace: reactive-rtc
spec:
  template:
    spec:
      containers:
      - name: load-balancer
        image: your-registry/load-balancer:latest
        env:
        - name: PROMETHEUS_HOST
          # If Prometheus is in the same namespace:
          value: "prometheus-service"
          # If Prometheus is in a different namespace (e.g., monitoring):
          # value: "prometheus-service.monitoring.svc.cluster.local"
        - name: PROMETHEUS_PORT
          value: "9090"
```

## API Endpoints

### 1. Metrics Summary (Recommended)

Get all key metrics in one call - perfect for autoscaling decisions.

**Endpoint:** `GET /api/v1/query/metrics-summary`

**Example:**
```bash
curl http://load-balancer:8081/api/v1/query/metrics-summary
```

**Response:**
```json
{
  "totalThroughput": 12500.5,
  "p95LatencySeconds": 0.245,
  "totalConnections": 8500,
  "totalConsumerLag": 120,
  "nodeCount": 3,
  "p95LatencyMs": 245.0,
  "throughputPerNode": 4166.83,
  "connectionsPerNode": 2833.33,
  "lagPerNode": 40.0
}
```

**Use Case:**
```bash
# Check if system needs scaling
METRICS=$(curl -s http://load-balancer:8081/api/v1/query/metrics-summary)
P95_MS=$(echo $METRICS | jq -r '.p95LatencyMs')

if (( $(echo "$P95_MS > 500" | bc -l) )); then
  echo "Scale out: p95 latency is ${P95_MS}ms (SLO: 500ms)"
  kubectl scale deployment socket-deployment --replicas=5
fi
```

### 2. Throughput by Node

Get message throughput for each socket node.

**Endpoint:** `GET /api/v1/query/throughput-by-node`

**Example:**
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

### 3. Kafka Consumer Lag by Node

Get Kafka consumer lag for each socket node.

**Endpoint:** `GET /api/v1/query/kafka-lag-by-node`

**Example:**
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

### 4. Custom PromQL Query

Execute any PromQL query through the load-balancer.

**Endpoint:** `GET /api/v1/query/promql?query=<PROMQL>`

**Example:**
```bash
# Get total active connections
curl "http://load-balancer:8081/api/v1/query/promql?query=sum(rtc_socket_active_connections)"

# Get p99 latency
curl "http://load-balancer:8081/api/v1/query/promql?query=histogram_quantile(0.99,%20rate(rtc_socket_latency_seconds_bucket[5m]))"
```

**Response:**
```json
{
  "value": 8500.0,
  "resultCount": 1,
  "results": [
    {
      "metric": {},
      "value": [1700000000, "8500"]
    }
  ]
}
```

## Kubernetes Integration

### Service Discovery

Prometheus in Kubernetes uses DNS-based service discovery:

```
# Same namespace
http://prometheus-service:9090

# Different namespace
http://prometheus-service.<namespace>.svc.cluster.local:9090

# With cluster domain
http://prometheus-service.<namespace>.svc.<cluster-domain>:9090
```

### Example Prometheus Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: prometheus-service
  namespace: monitoring
  labels:
    app: prometheus
spec:
  type: ClusterIP
  ports:
  - port: 9090
    targetPort: 9090
    name: http
  selector:
    app: prometheus
```

### Load-Balancer Configuration for K8s

**Option 1: Prometheus in same namespace (reactive-rtc)**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: load-balancer
  namespace: reactive-rtc
spec:
  template:
    spec:
      containers:
      - name: load-balancer
        env:
        - name: PROMETHEUS_HOST
          value: "prometheus-service"  # Short name works
        - name: PROMETHEUS_PORT
          value: "9090"
```

**Option 2: Prometheus in different namespace (monitoring)**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: load-balancer
  namespace: reactive-rtc
spec:
  template:
    spec:
      containers:
      - name: load-balancer
        env:
        - name: PROMETHEUS_HOST
          value: "prometheus-service.monitoring.svc.cluster.local"  # FQDN
        - name: PROMETHEUS_PORT
          value: "9090"
```

**Option 3: Using ConfigMap**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: load-balancer-config
  namespace: reactive-rtc
data:
  PROMETHEUS_HOST: "prometheus-service.monitoring.svc.cluster.local"
  PROMETHEUS_PORT: "9090"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: load-balancer
  namespace: reactive-rtc
spec:
  template:
    spec:
      containers:
      - name: load-balancer
        envFrom:
        - configMapRef:
            name: load-balancer-config
```

## Kubernetes HPA Integration

Use the metrics API to drive Horizontal Pod Autoscaler decisions:

### Example: Custom Metrics HPA

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: socket-hpa
  namespace: reactive-rtc
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: socket-deployment
  minReplicas: 2
  maxReplicas: 10
  
  # Use external metrics adapter to query load-balancer API
  metrics:
  - type: External
    external:
      metric:
        name: rtc_socket_p95_latency_ms
        selector:
          matchLabels:
            app: socket
      target:
        type: Value
        value: "500"  # Scale when p95 > 500ms

  - type: External
    external:
      metric:
        name: rtc_socket_kafka_consumer_lag
        selector:
          matchLabels:
            app: socket
      target:
        type: Value
        value: "1000"  # Scale when lag > 1000 messages
```

### Example: Script-Based Autoscaling

If you don't have a custom metrics adapter, use a CronJob:

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: autoscaler-cron
  namespace: reactive-rtc
spec:
  schedule: "*/2 * * * *"  # Every 2 minutes
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: autoscaler
            image: curlimages/curl:latest
            command:
            - /bin/sh
            - -c
            - |
              # Get metrics summary
              METRICS=$(curl -s http://load-balancer:8081/api/v1/query/metrics-summary)
              
              # Parse metrics
              P95_MS=$(echo $METRICS | grep -o '"p95LatencyMs":[0-9.]*' | cut -d: -f2)
              TOTAL_LAG=$(echo $METRICS | grep -o '"totalConsumerLag":[0-9]*' | cut -d: -f2)
              NODE_COUNT=$(echo $METRICS | grep -o '"nodeCount":[0-9]*' | cut -d: -f2)
              
              # Scale out conditions
              SHOULD_SCALE_OUT=0
              
              if (( $(echo "$P95_MS > 500" | bc -l) )); then
                echo "High latency: ${P95_MS}ms > 500ms"
                SHOULD_SCALE_OUT=1
              fi
              
              if [ "$TOTAL_LAG" -gt 1000 ]; then
                echo "High Kafka lag: ${TOTAL_LAG} > 1000"
                SHOULD_SCALE_OUT=1
              fi
              
              # Scale out
              if [ "$SHOULD_SCALE_OUT" -eq 1 ]; then
                NEW_REPLICAS=$((NODE_COUNT + 2))
                echo "Scaling out to $NEW_REPLICAS replicas"
                kubectl scale deployment socket-deployment --replicas=$NEW_REPLICAS -n reactive-rtc
              fi
              
              # Scale in conditions (all must be true)
              if (( $(echo "$P95_MS < 200" | bc -l) )) && [ "$TOTAL_LAG" -lt 500 ] && [ "$NODE_COUNT" -gt 2 ]; then
                NEW_REPLICAS=$((NODE_COUNT - 1))
                echo "Scaling in to $NEW_REPLICAS replicas"
                kubectl scale deployment socket-deployment --replicas=$NEW_REPLICAS -n reactive-rtc
              fi
          restartPolicy: OnFailure
          serviceAccountName: autoscaler-sa
---
# RBAC for autoscaler
apiVersion: v1
kind: ServiceAccount
metadata:
  name: autoscaler-sa
  namespace: reactive-rtc
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: autoscaler-role
  namespace: reactive-rtc
rules:
- apiGroups: ["apps"]
  resources: ["deployments", "deployments/scale"]
  verbs: ["get", "patch", "update"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: autoscaler-rolebinding
  namespace: reactive-rtc
subjects:
- kind: ServiceAccount
  name: autoscaler-sa
roleRef:
  kind: Role
  name: autoscaler-role
  apiGroup: rbac.authorization.k8s.io
```

## Usage Examples

### Python Script

```python
import requests
import json

LOAD_BALANCER_URL = "http://load-balancer:8081"

def get_metrics_summary():
    response = requests.get(f"{LOAD_BALANCER_URL}/api/v1/query/metrics-summary")
    return response.json()

def should_scale_out(metrics, config):
    """Determine if we should scale out based on metrics."""
    reasons = []
    
    # Check latency SLO
    if metrics['p95LatencyMs'] > config['l_slo_ms']:
        reasons.append(f"Latency {metrics['p95LatencyMs']}ms > SLO {config['l_slo_ms']}ms")
    
    # Check throughput capacity
    if metrics['throughputPerNode'] > config['mps_per_pod']:
        reasons.append(f"Throughput {metrics['throughputPerNode']}/node > capacity {config['mps_per_pod']}/pod")
    
    # Check Kafka lag
    if metrics['totalConsumerLag'] > 1000:
        reasons.append(f"Kafka lag {metrics['totalConsumerLag']} > 1000 messages")
    
    return reasons

def main():
    metrics = get_metrics_summary()
    config = {
        'l_slo_ms': 500,
        'mps_per_pod': 10000
    }
    
    scale_out_reasons = should_scale_out(metrics, config)
    
    if scale_out_reasons:
        print("SCALE OUT needed:")
        for reason in scale_out_reasons:
            print(f"  - {reason}")
        print(f"Current nodes: {metrics['nodeCount']}")
        print(f"Recommended: {metrics['nodeCount'] + 2} nodes")
    else:
        print("System is healthy")
        print(f"  p95 latency: {metrics['p95LatencyMs']:.2f}ms")
        print(f"  Throughput: {metrics['totalThroughput']:.2f} msg/s")
        print(f"  Connections: {metrics['totalConnections']}")
        print(f"  Kafka lag: {metrics['totalConsumerLag']}")

if __name__ == "__main__":
    main()
```

### Bash Script

```bash
#!/bin/bash

LOAD_BALANCER_URL="http://load-balancer:8081"
L_SLO_MS=500
MAX_LAG=1000

# Get metrics summary
METRICS=$(curl -s "$LOAD_BALANCER_URL/api/v1/query/metrics-summary")

# Parse JSON (requires jq)
P95_MS=$(echo $METRICS | jq -r '.p95LatencyMs')
TOTAL_LAG=$(echo $METRICS | jq -r '.totalConsumerLag')
NODE_COUNT=$(echo $METRICS | jq -r '.nodeCount')
THROUGHPUT=$(echo $METRICS | jq -r '.totalThroughput')

echo "Current Metrics:"
echo "  p95 Latency: ${P95_MS}ms"
echo "  Kafka Lag: ${TOTAL_LAG} messages"
echo "  Nodes: ${NODE_COUNT}"
echo "  Throughput: ${THROUGHPUT} msg/s"

# Check if scaling is needed
SCALE_OUT=false

if (( $(echo "$P95_MS > $L_SLO_MS" | bc -l) )); then
  echo "WARNING: Latency ${P95_MS}ms exceeds SLO ${L_SLO_MS}ms"
  SCALE_OUT=true
fi

if [ "$TOTAL_LAG" -gt "$MAX_LAG" ]; then
  echo "WARNING: Kafka lag ${TOTAL_LAG} exceeds threshold ${MAX_LAG}"
  SCALE_OUT=true
fi

if [ "$SCALE_OUT" = true ]; then
  NEW_REPLICAS=$((NODE_COUNT + 2))
  echo "RECOMMENDATION: Scale out to ${NEW_REPLICAS} replicas"
  
  # Uncomment to actually scale:
  # kubectl scale deployment socket-deployment --replicas=$NEW_REPLICAS -n reactive-rtc
else
  echo "System is healthy - no scaling needed"
fi
```

## Troubleshooting

### Cannot connect to Prometheus

**Symptoms:**
```
Failed to query Prometheus: Connection refused
```

**Solution:**
```bash
# Test Prometheus connectivity from load-balancer pod
kubectl exec -it deployment/load-balancer -n reactive-rtc -- sh
wget -O- http://prometheus-service.monitoring.svc.cluster.local:9090/-/healthy

# Check if Prometheus service exists
kubectl get svc -n monitoring

# Check if Prometheus is running
kubectl get pods -n monitoring | grep prometheus

# Verify DNS resolution
kubectl exec -it deployment/load-balancer -n reactive-rtc -- nslookup prometheus-service.monitoring.svc.cluster.local
```

### Empty or null metrics

**Symptoms:**
```json
{
  "value": null,
  "totalThroughput": 0.0
}
```

**Possible Causes:**
1. Socket nodes not sending metrics
2. Prometheus not scraping socket nodes
3. Wrong metric names or labels

**Solution:**
```bash
# Check if Prometheus is scraping socket nodes
curl http://prometheus:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job=="socket-nodes")'

# Test query directly in Prometheus
curl "http://prometheus:9090/api/v1/query?query=rtc_socket_active_connections"

# Check socket node metrics endpoint
curl http://socket-1:8080/metrics | grep rtc_socket
```

### Query timeouts

**Symptoms:**
```
Query timeout after 5 seconds
```

**Solution:**
- Reduce query time range (e.g., `[5m]` instead of `[1h]`)
- Use recording rules in Prometheus for expensive queries
- Increase timeout in `PrometheusQueryService` constructor

## Best Practices

1. **Use metrics-summary endpoint** for autoscaling - it's optimized and provides all key metrics
2. **Cache results** if querying frequently (implement caching layer)
3. **Set appropriate timeouts** - metrics queries should be fast (<1s)
4. **Monitor the monitor** - track Prometheus query failures
5. **Use FQDN in Kubernetes** - more reliable than short names across namespaces
6. **Implement circuit breaker** - don't let Prometheus failures break autoscaling

## Summary

### Quick Reference

| Endpoint | Use Case |
|----------|----------|
| `/api/v1/query/metrics-summary` | Get all metrics for autoscaling |
| `/api/v1/query/throughput-by-node` | Check node-specific load |
| `/api/v1/query/kafka-lag-by-node` | Monitor consumer health |
| `/api/v1/query/promql?query=...` | Custom queries |

### Kubernetes Setup

```bash
# 1. Configure Prometheus host
kubectl set env deployment/load-balancer \
  PROMETHEUS_HOST=prometheus-service.monitoring.svc.cluster.local \
  -n reactive-rtc

# 2. Test connectivity
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -n reactive-rtc -- \
  curl http://load-balancer:8081/api/v1/query/metrics-summary

# 3. Set up autoscaling (see examples above)
```

For more details, see:
- [KAFKA_MONITORING_GUIDE.md](./KAFKA_MONITORING_GUIDE.md) - Kafka metrics
- [METRICS_RECORDING_SUMMARY.md](./METRICS_RECORDING_SUMMARY.md) - Application metrics


