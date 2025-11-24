# Kafka Consumer Groups Troubleshooting

## Issue: Only One Node Shows in Kafka Charts

If you're only seeing `socket-delivery-socket-node-2` in Kafka consumer lag charts, but not other nodes, here's how to diagnose and fix it.

## Root Cause

**Only socket-node-2's consumer group is registered with Kafka.**

This happens when:
1. Only socket-node-2 is actually running
2. Other socket nodes haven't started their Kafka consumers yet
3. Other socket nodes failed to connect to Kafka
4. Consumer groups were created but haven't consumed any messages yet

## Diagnostic Steps

### 1. Check Which Socket Nodes are Running

```bash
# Docker Compose
docker-compose ps | grep socket

# Kubernetes
kubectl get pods -n reactive-rtc | grep socket
```

**Expected:** You should see socket-1, socket-2, etc. all running

### 2. Check Which Consumer Groups Exist in Kafka

```bash
# Docker
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list

# Expected output:
# socket-delivery-socket-node-1
# socket-delivery-socket-node-2
# socket-control-socket-node-1
# socket-control-socket-node-2
```

**If only socket-delivery-socket-node-2 appears:**
→ Other nodes haven't started their Kafka consumers

### 3. Check Socket Node Logs

```bash
# Docker Compose
docker logs socket-1 | grep -i kafka
docker logs socket-2 | grep -i kafka

# Kubernetes
kubectl logs deployment/socket-deployment -n reactive-rtc | grep -i kafka
```

**Look for:**
```
✓ GOOD: "Kafka consumers started for node socket-node-1"
✓ GOOD: "Node socket-node-1 subscribed to delivery topic: delivery_node_socket-node-1"

✗ BAD: "Failed to create Kafka topic"
✗ BAD: "Connection refused: kafka:9092"
✗ BAD: "Consumer group coordination failed"
```

### 4. Query Kafka Exporter Directly

```bash
# Check what consumer groups Kafka Exporter sees
curl -s http://localhost:9308/metrics | grep kafka_consumergroup_members

# Expected:
# kafka_consumergroup_members{consumergroup="socket-delivery-socket-node-1"} 1
# kafka_consumergroup_members{consumergroup="socket-delivery-socket-node-2"} 1
```

**If only node-2 shows:**
→ Kafka Exporter correctly reflects Kafka's state
→ The problem is in Kafka, not Grafana

### 5. Check Prometheus Scraping

```bash
# Query Prometheus directly
curl -s 'http://localhost:9090/api/v1/query?query=kafka_consumergroup_lag_sum' | jq '.data.result[] | {consumergroup: .metric.consumergroup, value: .value[1]}'

# Expected: Multiple consumer groups
```

## Common Solutions

### Solution 1: Restart Socket Nodes

```bash
# Docker Compose
docker-compose restart socket-1 socket-2

# Kubernetes
kubectl rollout restart deployment/socket-deployment -n reactive-rtc
```

**Wait 30 seconds**, then check consumer groups again.

### Solution 2: Ensure All Nodes Connected to Kafka

Check that socket-1 has successfully connected:

```bash
# Check socket-1 logs
docker logs socket-1 2>&1 | grep "Kafka consumers started"

# Should see:
# Kafka consumers started for node socket-node-1
```

**If you don't see this log:**
→ Socket-1 failed to start Kafka consumers

**Check for errors:**
```bash
docker logs socket-1 2>&1 | grep -i "error\|failed\|exception"
```

### Solution 3: Trigger Consumer Group Registration

Consumer groups only appear in Kafka after they start consuming. Force them to consume:

```bash
# Send a test message to trigger consumption
# This will create the consumer group if it doesn't exist

# Via load-balancer API
curl -X POST http://localhost:8081/api/v1/nodes/heartbeat \
  -H "Content-Type: application/json" \
  -d '{
    "nodeId": "socket-node-1",
    "activeConnections": 0,
    "deliveryRate": 0.0,
    "p95Latency": 0.0,
    "timestamp": 1700000000
  }'
```

### Solution 4: Check Kafka Topics Exist

```bash
# List topics
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list

# Expected topics:
# delivery_node_socket-node-1
# delivery_node_socket-node-2
# control_ring
```

**If topics are missing:**

```bash
# They should be created automatically by KafkaService
# Check logs for topic creation:
docker logs socket-1 | grep "Creating Kafka topic"
```

### Solution 5: Check Consumer Group Details

```bash
# Describe specific consumer group
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group socket-delivery-socket-node-1

# Should show:
# GROUP                           TOPIC               PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# socket-delivery-socket-node-1   delivery_node_...   0          123             123             0
```

**If command fails:**
→ Consumer group doesn't exist
→ Node hasn't started consuming yet

## Grafana Query Fixes

The dashboard now uses these corrected queries:

### Panel 25: Kafka Consumer Lag (all nodes)
```promql
sum by (consumergroup) (kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"})
```

**This creates one series per consumer group**

### Panel 26: Kafka Consumer Lag per Partition (all nodes)
```promql
sum by (consumergroup, partition) (kafka_consumergroup_lag{consumergroup=~"socket-delivery-.*"})
```

### Panel 29: Consumer Offset Rate (all nodes)
```promql
sum by (consumergroup) (rate(kafka_consumergroup_current_offset_sum{consumergroup=~"socket-delivery-.*"}[1m]))
```

## Verify the Fix

### Test Query in Prometheus

```bash
# Open Prometheus
open http://localhost:9090

# Run query:
sum by (consumergroup) (kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"})

# You should see multiple series:
# {consumergroup="socket-delivery-socket-node-1"} → 0
# {consumergroup="socket-delivery-socket-node-2"} → 5
```

**If only one series appears:**
→ Only one consumer group is registered in Kafka
→ This is a Kafka issue, not a Grafana issue

## Quick Health Check

```bash
#!/bin/bash

echo "=== Socket Nodes Status ==="
docker-compose ps | grep socket

echo -e "\n=== Kafka Consumer Groups ==="
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list | grep socket

echo -e "\n=== Kafka Topics ==="
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list | grep delivery_node

echo -e "\n=== Kafka Exporter Metrics ==="
curl -s http://localhost:9308/metrics | grep "kafka_consumergroup_members{consumergroup=" | grep socket-delivery

echo -e "\n=== Expected Consumer Groups ==="
echo "socket-delivery-socket-node-1"
echo "socket-delivery-socket-node-2"
echo "socket-control-socket-node-1"
echo "socket-control-socket-node-2"
```

Save as `check-kafka-consumers.sh` and run it to diagnose the issue.

## Most Likely Cause

**If only node-2 shows up consistently:**

1. **Check socket-1 container:**
   ```bash
   docker logs socket-1 | tail -50
   ```
   
2. **Look for Kafka connection:**
   ```bash
   docker logs socket-1 | grep "Kafka consumers started"
   ```
   
3. **If socket-1 isn't logging Kafka startup:**
   → Socket-1 didn't start successfully
   → Check for errors during startup
   → Verify environment variables are correct

## Force Consumer Group Creation

If needed, manually trigger Kafka consumer creation:

```bash
# Restart all socket nodes in sequence
docker-compose restart socket-1
sleep 10  # Wait for Kafka consumer to register
docker-compose restart socket-2
sleep 10

# Verify consumer groups
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list | grep socket-delivery
```

## Summary

**The Grafana dashboard queries are now correct.** If you still only see one node:

1. ✓ **Grafana queries** - Fixed (shows all consumer groups)
2. ✓ **Kafka Exporter** - Working (exports what Kafka reports)
3. ✗ **Kafka** - Only one consumer group is registered
4. ✗ **Socket nodes** - Only one node successfully started Kafka consumers

**Next step:** Check socket node logs and Kafka consumer groups to identify why other nodes aren't connecting.

