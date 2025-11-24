# Kafka Monitoring Guide

This guide explains how to monitor Kafka consumer lag and use these metrics for system health monitoring and autoscaling decisions.

## Overview

We use [danielqsj/kafka_exporter](https://github.com/danielqsj/kafka_exporter) to expose Kafka metrics to Prometheus. This gives us visibility into:

- **Consumer group lag** - How far behind consumers are from producers
- **Topic offsets** - Current state of topics and partitions
- **Broker health** - Number of brokers and their status
- **Consumer group members** - Active consumers in each group

## Deployment

### Local Development (Docker Compose)

```bash
# Start the full stack including Kafka Exporter
docker-compose -f docker-compose.yml -f deploy/docker-compose.prometheus.yml up -d

# Check Kafka Exporter health
curl http://localhost:9308/metrics | grep kafka_

# Access Prometheus UI
open http://localhost:9090

# Access Grafana
open http://localhost:3000  # admin/admin
```

### Kubernetes Deployment

Add to your `socket-deploy.yaml`:

```yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kafka-exporter
  namespace: reactive-rtc
spec:
  replicas: 1
  selector:
    matchLabels:
      app: kafka-exporter
  template:
    metadata:
      labels:
        app: kafka-exporter
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "9308"
        prometheus.io/path: "/metrics"
    spec:
      containers:
      - name: kafka-exporter
        image: danielqsj/kafka-exporter:latest
        args:
          - --kafka.server=kafka-service:9092
          - --web.listen-address=:9308
          - --log.level=info
          - --topic.filter=.*
          - --group.filter=.*
        ports:
        - name: metrics
          containerPort: 9308
        resources:
          requests:
            memory: "128Mi"
            cpu: "100m"
          limits:
            memory: "256Mi"
            cpu: "500m"
---
apiVersion: v1
kind: Service
metadata:
  name: kafka-exporter
  namespace: reactive-rtc
  labels:
    app: kafka-exporter
spec:
  type: ClusterIP
  ports:
  - port: 9308
    targetPort: 9308
    name: metrics
  selector:
    app: kafka-exporter
```

## Key Metrics

### 1. Consumer Group Lag

**Most Critical Metric for Your System**

#### Metrics:

```promql
# Lag per partition
kafka_consumergroup_lag{consumergroup="socket-delivery-socket-node-1", topic="delivery_node_socket-node-1", partition="0"}

# Total lag per topic (all partitions)
kafka_consumergroup_lag_sum{consumergroup="socket-delivery-socket-node-1", topic="delivery_node_socket-node-1"}
```

#### What It Means:

- **Lag = 0-100**: Healthy, consumer keeping up
- **Lag = 100-1000**: Warning, consumer falling behind slightly
- **Lag > 1000**: Critical, consumer significantly behind, messages piling up
- **Lag increasing over time**: Consumer can't keep up with producer rate

#### Your Consumer Groups:

Based on your `KafkaService.java`:

```java
// Each socket node has its own consumer group
String groupId = "socket-delivery-" + currentNodeId;
// Examples: socket-delivery-socket-node-1, socket-delivery-socket-node-2
```

### 2. Consumer Group Current Offset

```promql
# Current offset consumer has read
kafka_consumergroup_current_offset{consumergroup="socket-delivery-socket-node-1", topic="delivery_node_socket-node-1"}

# Sum across all partitions
kafka_consumergroup_current_offset_sum{consumergroup="socket-delivery-socket-node-1", topic="delivery_node_socket-node-1"}
```

### 3. Topic Partition Current Offset

```promql
# Latest offset available in topic
kafka_topic_partition_current_offset{topic="delivery_node_socket-node-1", partition="0"}
```

**Lag Calculation:**
```
Consumer Lag = Topic Current Offset - Consumer Current Offset
```

### 4. Topic Metadata

```promql
# Number of partitions per topic
kafka_topic_partitions{topic="delivery_node_socket-node-1"}

# Under-replicated partitions (should be 0)
kafka_topic_partition_under_replicated_partition{topic="delivery_node_socket-node-1"}

# In-sync replicas (should equal replication factor)
kafka_topic_partition_in_sync_replica{topic="delivery_node_socket-node-1"}
```

### 5. Consumer Group Members

```promql
# Number of active consumers in group (should be 1 per socket node)
kafka_consumergroup_members{consumergroup="socket-delivery-socket-node-1"}
```

### 6. Broker Health

```promql
# Number of Kafka brokers (should match your cluster size)
kafka_brokers

# Broker information
kafka_broker_info{id="1", address="kafka:9092"}
```

## Prometheus Queries for Monitoring

### Overall System Health

```promql
# Total lag across all socket nodes
sum(kafka_consumergroup_lag_sum) by (consumergroup)

# Maximum lag across any partition
max(kafka_consumergroup_lag) by (consumergroup, topic)

# Average lag per consumer group
avg(kafka_consumergroup_lag_sum) by (consumergroup)
```

### Per-Node Health

```promql
# Lag for specific socket node
kafka_consumergroup_lag_sum{consumergroup="socket-delivery-socket-node-1"}

# Rate of consumption (messages/sec)
rate(kafka_consumergroup_current_offset_sum{consumergroup="socket-delivery-socket-node-1"}[1m])
```

### Production Rate

```promql
# Rate of messages produced to topics (messages/sec)
rate(kafka_topic_partition_current_offset{topic=~"delivery_node_.*"}[1m])

# Total production rate across all topics
sum(rate(kafka_topic_partition_current_offset{topic=~"delivery_node_.*"}[1m]))
```

### Consumer Efficiency

```promql
# Consumption rate vs production rate ratio
(
  rate(kafka_consumergroup_current_offset_sum[1m])
  /
  rate(kafka_topic_partition_current_offset[1m])
)

# Values:
# < 1.0 = Consumer falling behind
# = 1.0 = Keeping up perfectly
# > 1.0 = Consumer catching up on backlog
```

## Alerting Rules

Add these to your Prometheus alerting rules:

```yaml
groups:
- name: kafka_alerts
  interval: 30s
  rules:
  
  # Critical: High consumer lag
  - alert: KafkaConsumerLagHigh
    expr: kafka_consumergroup_lag_sum > 1000
    for: 5m
    labels:
      severity: critical
    annotations:
      summary: "High Kafka consumer lag for {{ $labels.consumergroup }}"
      description: "Consumer group {{ $labels.consumergroup }} has lag of {{ $value }} messages on topic {{ $labels.topic }}"

  # Warning: Consumer lag increasing
  - alert: KafkaConsumerLagIncreasing
    expr: delta(kafka_consumergroup_lag_sum[5m]) > 100
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Kafka consumer lag increasing for {{ $labels.consumergroup }}"
      description: "Consumer lag increased by {{ $value }} messages in 5 minutes"

  # Critical: No consumer group members
  - alert: KafkaConsumerGroupEmpty
    expr: kafka_consumergroup_members == 0
    for: 2m
    labels:
      severity: critical
    annotations:
      summary: "No active consumers in group {{ $labels.consumergroup }}"
      description: "Consumer group {{ $labels.consumergroup }} has no active members"

  # Critical: Under-replicated partitions
  - alert: KafkaUnderReplicatedPartitions
    expr: kafka_topic_partition_under_replicated_partition > 0
    for: 5m
    labels:
      severity: critical
    annotations:
      summary: "Under-replicated partitions detected"
      description: "Topic {{ $labels.topic }} partition {{ $labels.partition }} is under-replicated"

  # Warning: Broker count changed
  - alert: KafkaBrokerCountChanged
    expr: changes(kafka_brokers[5m]) > 0
    labels:
      severity: warning
    annotations:
      summary: "Kafka broker count changed"
      description: "Number of Kafka brokers changed to {{ $value }}"
```

## Autoscaling Based on Kafka Lag

### Combined Autoscaling Rule

Scale socket nodes when:
1. Consumer lag is high (backlog)
2. OR end-to-end latency is high (SLO violation)
3. OR throughput is high (capacity)

```promql
# Scale OUT when any condition is true:
(
  # Condition 1: High consumer lag (messages piling up)
  sum(kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"}) > 5000
  
  OR
  
  # Condition 2: p95 latency exceeds SLO (500ms)
  histogram_quantile(0.95, rate(rtc_socket_latency_seconds_bucket[5m])) > 0.5
  
  OR
  
  # Condition 3: High throughput (exceeds capacity)
  sum(rate(rtc_socket_deliver_mps_total[1m])) > (count(up{job="socket-nodes"}) * 10000)
)

# Scale IN when all conditions are met:
(
  # Condition 1: Low consumer lag
  sum(kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"}) < 500
  
  AND
  
  # Condition 2: Low latency
  histogram_quantile(0.95, rate(rtc_socket_latency_seconds_bucket[5m])) < 0.2
  
  AND
  
  # Condition 3: Low throughput
  sum(rate(rtc_socket_deliver_mps_total[1m])) < (count(up{job="socket-nodes"}) * 5000)
)
```

### Kubernetes HPA with Custom Metrics

Example HPA configuration:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: socket-nodes-hpa
  namespace: reactive-rtc
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: socket-deployment
  minReplicas: 2
  maxReplicas: 10
  metrics:
  
  # Scale based on consumer lag
  - type: Pods
    pods:
      metric:
        name: kafka_consumergroup_lag_sum
      target:
        type: AverageValue
        averageValue: "1000"  # Scale when avg lag > 1000/pod
  
  # Scale based on throughput (from your app metrics)
  - type: Pods
    pods:
      metric:
        name: rtc_socket_deliver_mps_rate
      target:
        type: AverageValue
        averageValue: "10000"  # Scale when avg > 10k msg/s per pod
  
  # Scale based on latency (from your app metrics)
  - type: Pods
    pods:
      metric:
        name: rtc_socket_latency_p95
      target:
        type: AverageValue
        averageValue: "0.5"  # Scale when p95 > 500ms

  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300  # 5 min cooldown
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 60  # 1 min cooldown
      policies:
      - type: Percent
        value: 100  # Double capacity rapidly
        periodSeconds: 30
```

## Grafana Dashboard

### Import Pre-built Dashboard

1. Open Grafana: http://localhost:3000
2. Navigate to **Dashboards** → **Import**
3. Enter Dashboard ID: **7589**
4. Name: "Kafka Exporter Overview"
5. Select your Prometheus datasource
6. Click **Import**

This gives you a comprehensive view of:
- Consumer group lag over time
- Topic throughput
- Broker status
- Partition health

### Custom Panels for Your Use Case

Add these panels to monitor your specific consumer groups:

#### Panel 1: Socket Node Consumer Lag
```promql
kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"}
```

#### Panel 2: Message Production vs Consumption Rate
```promql
# Production rate
sum(rate(kafka_topic_partition_current_offset{topic=~"delivery_node_.*"}[1m]))

# Consumption rate
sum(rate(kafka_consumergroup_current_offset_sum{consumergroup=~"socket-delivery-.*"}[1m]))
```

#### Panel 3: Consumer Lag Heatmap
```promql
kafka_consumergroup_lag{consumergroup=~"socket-delivery-.*"}
```

#### Panel 4: Per-Node Lag
```promql
sum by (consumergroup) (kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"})
```

## Troubleshooting

### High Consumer Lag

**Symptoms:**
```promql
kafka_consumergroup_lag_sum > 1000
```

**Possible Causes:**
1. **Consumer too slow** - Check CPU/memory on socket pods
2. **High message rate** - Producer sending faster than consumer can handle
3. **Network issues** - Kafka broker or socket node network problems
4. **Processing delays** - Redis/WebSocket delivery bottlenecks

**Resolution:**
```bash
# Check consumer group details
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group socket-delivery-socket-node-1

# Scale up socket nodes
kubectl scale deployment socket-deployment --replicas=5

# Check socket node logs
kubectl logs -f deployment/socket-deployment
```

### No Metrics Appearing

**Check Kafka Exporter logs:**
```bash
docker logs kafka-exporter
# or
kubectl logs -f deployment/kafka-exporter
```

**Verify connectivity:**
```bash
# Test Kafka connection
docker exec kafka-exporter kafka-topics.sh --bootstrap-server kafka:9092 --list

# Check metrics endpoint
curl http://localhost:9308/metrics
```

### Consumer Group Not Showing Up

Consumer groups only appear after they've started consuming. Make sure:
1. Socket nodes are running
2. They've subscribed to their topics
3. At least one message has been consumed

```bash
# List all consumer groups
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
```

## Best Practices

### 1. Set Appropriate Lag Thresholds

```promql
# For your system, reasonable thresholds:
# - Normal: 0-500 messages lag
# - Warning: 500-2000 messages lag
# - Critical: > 2000 messages lag
```

### 2. Monitor Lag Trends

Don't just monitor absolute lag - monitor the **rate of change**:

```promql
# Lag increase rate (messages/minute)
delta(kafka_consumergroup_lag_sum[5m]) / 5
```

### 3. Correlate with Your Application Metrics

Always look at Kafka lag alongside:
- `rtc_socket_deliver_mps` (throughput)
- `rtc_socket_latency` (end-to-end latency)
- `rtc_socket_active_connections` (connection count)

### 4. Regular Health Checks

```promql
# Daily max lag
max_over_time(kafka_consumergroup_lag_sum[24h])

# Lag percentiles
histogram_quantile(0.95, kafka_consumergroup_lag)
```

## References

- [Kafka Exporter GitHub](https://github.com/danielqsj/kafka_exporter)
- [Grafana Dashboard #7589](https://grafana.com/grafana/dashboards/7589)
- [Prometheus Kafka Exporter Metrics](https://github.com/danielqsj/kafka_exporter#metrics)
- [Kafka Consumer Lag Monitoring Best Practices](https://www.confluent.io/blog/monitoring-kafka-consumer-lag/)

## Summary

### Critical Metrics to Monitor

| Metric | Threshold | Action |
|--------|-----------|--------|
| `kafka_consumergroup_lag_sum` | > 1000 | Scale out socket nodes |
| `kafka_consumergroup_members` | 0 | Alert - consumer down |
| `kafka_topic_partition_under_replicated_partition` | > 0 | Alert - Kafka unhealthy |
| Consumer lag delta | Increasing | Investigate consumer performance |

### Quick Commands

```bash
# Start monitoring stack
docker-compose -f docker-compose.yml -f deploy/docker-compose.prometheus.yml up -d

# View Kafka metrics
curl http://localhost:9308/metrics | grep kafka_consumergroup_lag

# Check Prometheus targets
open http://localhost:9090/targets

# Import Grafana dashboard
open http://localhost:3000/dashboard/import
# Enter: 7589
```

With these Kafka metrics, you now have complete visibility into your message relay system! 🚀


