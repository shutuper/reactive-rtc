# Kafka Exporter Quick Start

Monitor Kafka consumer lag and topic health with [danielqsj/kafka_exporter](https://github.com/danielqsj/kafka_exporter).

## 🚀 Quick Deploy

### Docker Compose (Local Development)

```bash
# Start everything including Kafka Exporter
docker-compose -f docker-compose.yml -f deploy/docker-compose.prometheus.yml up -d

# Verify Kafka Exporter is running
curl http://localhost:9308/metrics | grep kafka_consumergroup_lag

# Check Prometheus is scraping it
open http://localhost:9090/targets
# Look for "kafka-exporter" target
```

### Kubernetes

```bash
# Deploy Kafka Exporter
kubectl apply -f deploy/k8s/kafka-exporter-deploy.yaml

# Verify deployment
kubectl get pods -n reactive-rtc | grep kafka-exporter
kubectl logs -f -n reactive-rtc deployment/kafka-exporter

# Check metrics
kubectl port-forward -n reactive-rtc svc/kafka-exporter 9308:9308
curl http://localhost:9308/metrics | head -50
```

## 📊 Key Metrics

### Consumer Lag (Most Important!)

```promql
# Total lag per consumer group
kafka_consumergroup_lag_sum{consumergroup="socket-delivery-socket-node-1"}

# Per-partition lag
kafka_consumergroup_lag{consumergroup="socket-delivery-socket-node-1", partition="0"}

# Lag across all socket nodes
sum(kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"})
```

**What lag means:**
- **0-500**: ✅ Healthy
- **500-2000**: ⚠️ Warning - falling behind
- **>2000**: 🔴 Critical - scale immediately!

### Message Rates

```promql
# Production rate (messages/sec)
rate(kafka_topic_partition_current_offset{topic=~"delivery_node_.*"}[1m])

# Consumption rate (messages/sec)
rate(kafka_consumergroup_current_offset_sum{consumergroup=~"socket-delivery-.*"}[1m])

# Are consumers keeping up? (should be ~1.0)
rate(kafka_consumergroup_current_offset_sum[1m]) / rate(kafka_topic_partition_current_offset[1m])
```

### Consumer Health

```promql
# Active consumers per group (should be 1 per socket node)
kafka_consumergroup_members{consumergroup=~"socket-delivery-.*"}

# Number of Kafka brokers
kafka_brokers
```

## 🎯 Grafana Dashboard

### Import Official Dashboard

1. Go to: http://localhost:3000/dashboard/import
2. Enter ID: **7589**
3. Name: "Kafka Exporter Overview"
4. Select Prometheus datasource
5. Click Import

### Custom Panel for Your System

Create a new panel with this query:

```promql
# Socket Node Consumer Lag
kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"}
```

**Panel Settings:**
- Type: Time series
- Legend: `{{consumergroup}}`
- Y-axis: Messages
- Thresholds:
  - Green: 0-500
  - Yellow: 500-2000
  - Red: >2000

## 🚨 Critical Alerts

Add to Prometheus alerting rules:

```yaml
- alert: KafkaConsumerLagCritical
  expr: kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"} > 2000
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "Critical Kafka lag for {{ $labels.consumergroup }}"
    description: "Lag: {{ $value }} messages. Scale socket nodes immediately!"

- alert: KafkaConsumerDown
  expr: kafka_consumergroup_members{consumergroup=~"socket-delivery-.*"} == 0
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "Kafka consumer {{ $labels.consumergroup }} has no active members"
```

## 🔍 Troubleshooting

### No metrics appearing?

```bash
# Check Kafka Exporter logs
docker logs kafka-exporter
# or
kubectl logs -f deployment/kafka-exporter -n reactive-rtc

# Test Kafka connectivity
docker exec kafka-exporter kafka-topics --bootstrap-server kafka:9092 --list

# Verify Prometheus is scraping
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job=="kafka-exporter")'
```

### Consumer groups not showing?

Consumer groups only appear after they start consuming. Check:

```bash
# List consumer groups
docker exec kafka kafka-consumer-groups --bootstrap-server kafka:9092 --list

# Should see:
# socket-delivery-socket-node-1
# socket-delivery-socket-node-2
# socket-control-socket-node-1
# socket-control-socket-node-2
```

### High lag - what to do?

1. **Check socket node health:**
   ```bash
   kubectl top pods -n reactive-rtc | grep socket
   ```

2. **Scale up immediately:**
   ```bash
   kubectl scale deployment socket-deployment --replicas=5 -n reactive-rtc
   ```

3. **Check processing time:**
   ```promql
   histogram_quantile(0.95, rate(rtc_socket_latency_seconds_bucket[5m]))
   ```

4. **Inspect messages:**
   ```bash
   kubectl logs -f deployment/socket-deployment -n reactive-rtc | grep "Kafka message received"
   ```

## 📈 Autoscaling with Kafka Lag

### Combined Rule (Recommended)

Scale when **any** condition is met:

```promql
# Scale OUT
(
  kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"} > 1000
  OR
  histogram_quantile(0.95, rate(rtc_socket_latency_seconds_bucket[5m])) > 0.5
  OR
  rate(rtc_socket_deliver_mps_total[1m]) > (count(up{job="socket-nodes"}) * 10000)
)
```

### Kubernetes HPA Example

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
  metrics:
  - type: Pods
    pods:
      metric:
        name: kafka_consumergroup_lag_sum
      target:
        type: AverageValue
        averageValue: "1000"
```

## 🎓 Understanding Your Consumer Groups

Your `KafkaService.java` creates these consumer groups:

```java
// Delivery messages consumer group (per node)
String deliveryGroupId = "socket-delivery-" + nodeId;
// Examples: socket-delivery-socket-node-1, socket-delivery-socket-node-2

// Control messages consumer group (per node)  
String controlGroupId = "socket-control-" + nodeId;
// Examples: socket-control-socket-node-1, socket-control-socket-node-2
```

**Topics consumed:**
- `delivery_node_socket-node-1` (dedicated per-node topics)
- `delivery_node_socket-node-2`
- `control_ring` (shared control topic)

**Expected behavior:**
- Each socket node consumes only its own `delivery_node_*` topic
- 100% traffic efficiency (no wasted reads)
- Lag should stay near 0 under normal load

## 📚 Full Documentation

For detailed information, see:
- [KAFKA_MONITORING_GUIDE.md](./KAFKA_MONITORING_GUIDE.md) - Complete guide
- [Kafka Exporter GitHub](https://github.com/danielqsj/kafka_exporter) - Official docs
- [Grafana Dashboard 7589](https://grafana.com/grafana/dashboards/7589) - Pre-built dashboard

## ✅ Health Check Checklist

Before going to production, verify:

- [ ] Kafka Exporter running and metrics accessible
- [ ] Prometheus scraping Kafka Exporter (check targets)
- [ ] Consumer groups visible in metrics
- [ ] Grafana dashboard imported and working
- [ ] Alerts configured for high lag
- [ ] Autoscaling rules tested
- [ ] Lag thresholds appropriate for your traffic

## 🔗 Quick Links

- **Kafka Exporter Metrics**: http://localhost:9308/metrics
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)
- **Import Dashboard**: http://localhost:3000/dashboard/import (ID: 7589)

---

**Questions?** Check the full guide: [KAFKA_MONITORING_GUIDE.md](./KAFKA_MONITORING_GUIDE.md)



