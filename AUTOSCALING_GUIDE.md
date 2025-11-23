# 🎯 Auto-Scaling & Load Redistribution Guide

Complete guide to the enhanced autoscaling system with CPU, memory, and load redistribution.

---

## ✅ What's Implemented

### 1. **CPU & Memory-Based Scaling** ✅

Load-balancer now considers **CPU and memory utilization** in addition to connections, throughput, and latency.

**Scale Out Triggers:**
- Average CPU > 70% OR any node > 85%
- Average memory > 75% OR any node > 90%
- Total connections exceed capacity
- Throughput exceeds capacity  
- p95 latency exceeds SLO

**Scale In Triggers:**
- Average CPU < 30% AND average memory < 30% AND connections < 100
- Hysteresis: only scales down if 2+ replicas below target

### 2. **Automatic Load Redistribution** ✅

`LoadRedistributor` periodically (every 5 minutes) monitors load distribution and triggers graceful disconnections when load is uneven.

**Redistribution Triggers:**
- Coefficient of Variation > 0.5 (load very uneven)
- Any node has > 150% of average load

**How It Works:**
```
1. Checks load distribution every 5 minutes
2. Calculates coefficient of variation (CV)
3. If CV > 0.5 or node > 150% of avg load:
   - Identifies overloaded nodes
   - Calculates excess load (connections over 50% threshold)
   - Requests graceful disconnection of 20% of excess
4. Publishes DrainSignal to Kafka CONTROL_DRAIN topic
5. Socket nodes receive drain signals and gracefully disconnect users
6. Users reconnect and hash to evenly-distributed nodes
```

### 3. **Graceful Disconnection on Ring Changes** ✅

When ring topology changes (node joins/leaves), all nodes automatically receive:
- New ring version
- Updated hash ring snapshot
- Old connections gradually disconnect and reconnect to maintain even distribution

---

## 🎛️ How Scaling Works

### Scaling Formula

```
CPU factor: r_cpu = nodes * max(avgCpu, maxCpu) / 0.7
Memory factor: r_mem = nodes * max(avgMem, maxMem) / 0.75
Conn factor: r_conn = α * totalConn / connPerPod
MPS factor: r_mps = β * totalMps / mpsPerPod
Latency factor: r_lat = γ * exp(δ * max(0, p95/L_slo - 1))

targetReplicas = max(r_cpu, r_mem, r_conn, r_mps, r_lat)
```

### Example Scenarios

#### Scenario 1: Low Load (Scale In)
```
Nodes: 5
Avg CPU: 20%
Avg Memory: 25%
Connections: 50 total
Throughput: 10 msg/sec
p95 Latency: 10ms

Calculation:
- r_cpu = 0 (no CPU pressure)
- r_mem = 0 (no memory pressure)
- r_conn = ceil(1.2 * 50 / 5000) = 1
- r_mps = ceil(1.3 * 10 / 2500) = 1
- r_lat = 0 (no latency violation)

targetReplicas = 1
Action: SCALE_IN (reduce from 5 to 1-3 nodes)
```

#### Scenario 2: High CPU (Scale Out)
```
Nodes: 3
Avg CPU: 85%
Max CPU: 92%
Avg Memory: 45%
Connections: 8000 total

Calculation:
- r_cpu = ceil(3 * 0.92 / 0.7) = 4
- r_mem = 0
- r_conn = ceil(1.2 * 8000 / 5000) = 2
- r_mps = 1
- r_lat = 0

targetReplicas = 4
Action: SCALE_OUT (increase to 4 nodes)
```

#### Scenario 3: High Memory (Scale Out)
```
Nodes: 2
Avg CPU: 60%
Max CPU: 65%
Avg Memory: 82%
Max Memory: 95%
Connections: 5000 total

Calculation:
- r_cpu = 0 (below threshold)
- r_mem = ceil(2 * 0.95 / 0.75) = 3
- r_conn = ceil(1.2 * 5000 / 5000) = 2
- r_mps = 1
- r_lat = 0

targetReplicas = 3
Action: SCALE_OUT (increase to 3 nodes)
```

#### Scenario 4: Uneven Load (Redistribution)
```
Nodes: 4
Avg Load: 1000 connections
Max Load: 2500 connections
Min Load: 200 connections
CV: 0.8 (high variance)

Detection:
- Max load (2500) > 150% of avg (1500) ✅
- Triggers LoadRedistributor

Action:
- Node with 2500 connections receives DrainSignal
- Gracefully disconnect 400 connections (20% of excess)
- Users reconnect and redistribute evenly
```

---

## 📊 Monitoring Scaling Decisions

### View Scaling Metrics

```bash
# Load-balancer metrics
curl http://localhost:8081/metrics | grep scaling
```

**Output:**
```
rtc_lb_scaling_decisions_total{action="scale_out"} 5.0
rtc_lb_scaling_decisions_total{action="scale_in"} 2.0
rtc_lb_scaling_decisions_total{action="none"} 150.0
```

### Grafana Queries

**Scaling decisions over time:**
```promql
sum by (action) (rate(rtc_lb_scaling_decisions_total[5m]))
```

**Active nodes in ring:**
```promql
rtc_lb_ring_nodes
```

**Ring version (increases on topology changes):**
```promql
sum(rtc_socket_ring_version) by (nodeId)
```

---

## 🔧 Configuration

### Scaling Thresholds

**CPU scaling:**
```java
// Scale out if average > 70% or any node > 85%
if (avgCpu > 0.7 || maxCpu > 0.85) {
    rCpu = ceil(nodes * max(avgCpu, maxCpu) / 0.7)
}
```

**Memory scaling:**
```java
// Scale out if average > 75% or any node > 90%
if (avgMem > 0.75 || maxMem > 0.90) {
    rMem = ceil(nodes * max(avgMem, maxMem) / 0.75)
}
```

**Scale in (hysteresis):**
```java
// Only scale down if 2+ replicas below target
if (targetReplicas < currentReplicas - 1) {
    action = SCALE_IN
}
```

### Load Redistribution

**Frequency:** Every 5 minutes

**Thresholds:**
- Trigger if Coefficient of Variation > 0.5
- Trigger if any node > 150% of average load

**Disconnection rate:** 20% of excess load

---

## 🧪 Testing Auto-Scaling

### Test 1: CPU-Based Scale Out

```bash
# Simulate high CPU load
for i in {1..1000}; do
  # Connect users and keep connections active
  wscat -c "ws://localhost:8080/ws?userId=user$i" &
done

# Watch heartbeats (CPU/memory reported every 30s)
# After several heartbeats showing high CPU (>70%):
# Load-balancer should trigger SCALE_OUT
```

**Expected:**
```
INFO  ScalingEngine - Scaling directive: action=SCALE_OUT, target=3, reason="Demand exceeds capacity (conn=1000, mps=..., cpu=75.0%, mem=...)"
```

### Test 2: Low Load Scale In

```bash
# Connect only 50 users
for i in {1..50}; do
  wscat -c "ws://localhost:8080/ws?userId=user$i" &
done

# Wait several heartbeats
# If 5 nodes but only 50 connections, should scale in
```

**Expected:**
```
INFO  ScalingEngine - Scaling directive: action=SCALE_IN, target=2, reason="Low utilization (conn=50, cpu=15.0%, mem=20.0%)"
```

### Test 3: Load Redistribution

```bash
# Create uneven load
# Connect 1000 users to socket-1 only
for i in {1..1000}; do
  wscat -c "ws://localhost:8080/ws?userId=user$i" &
done

# Wait 5 minutes for redistribution check
# Should detect uneven load (CV > 0.5)
```

**Expected:**
```
INFO  LoadRedistributor - Uneven load detected (CV=0.8), triggering graceful disconnections
INFO  LoadRedistributor - Requesting 400 graceful disconnections from socket-1
```

---

## 📈 Metrics & Alerts

### Key Metrics

| Metric | Description | Threshold |
|--------|-------------|-----------|
| `rtc_lb_scaling_decisions_total{action="scale_out"}` | Scale-out decisions | Monitor rate |
| `rtc_lb_scaling_decisions_total{action="scale_in"}` | Scale-in decisions | Monitor rate |
| `rtc_lb_ring_nodes` | Active nodes in ring | Alert if = 0 |
| Average CPU across nodes | CPU utilization | Alert if > 80% |
| Average memory across nodes | Memory utilization | Alert if > 85% |

### Prometheus Alerts

```yaml
- alert: HighCPUAcrossCluster
  expr: avg(jvm_cpu_usage) > 0.8
  for: 5m
  annotations:
    summary: "High CPU utilization across cluster"

- alert: HighMemoryAcrossCluster
  expr: avg(jvm_memory_used / jvm_memory_max) > 0.85
  for: 5m
  annotations:
    summary: "High memory utilization across cluster"

- alert: NoActiveNodes
  expr: rtc_lb_ring_nodes == 0
  for: 1m
  severity: critical
  annotations:
    summary: "No active socket nodes"
```

---

## 🎯 Summary

### What Changed

✅ **CPU & Memory metrics** now trigger scaling decisions  
✅ **Load redistribution** automatically balances uneven loads  
✅ **DrainSignal** enables graceful disconnections for redistribution  
✅ **Enhanced scale-in logic** considers CPU/memory/utilization  
✅ **Periodic checks** every 5 minutes for load balance  

### Scaling Triggers

**Scale Out (5 triggers):**
1. CPU > 70% average or >85% on any node
2. Memory > 75% average or >90% on any node  
3. Connections exceed capacity
4. Throughput exceeds capacity
5. p95 latency exceeds SLO

**Scale In (hysteresis):**
- CPU < 30% AND memory < 30% AND connections < 100
- Must be 2+ replicas below target

**Redistribution:**
- CV > 0.5 (very uneven)
- Any node > 150% of average load

### Files Modified

- `ScalingEngine.java` - Added CPU/memory metrics
- `LoadRedistributor.java` - NEW load redistribution service
- `ControlMessages.DrainSignal` - Added maxDisconnects field
- `Topics.java` - Added CONTROL_DRAIN topic
- `KafkaPublisher.java` - Added publishDrainSignal()
- `LoadBalancerApp.java` - Integrated LoadRedistributor

---

**Your system now has intelligent, multi-factor autoscaling with automatic load redistribution!** 🚀











