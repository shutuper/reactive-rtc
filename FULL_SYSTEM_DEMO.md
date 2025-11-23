# 🚀 Full System Demo: Launch, Monitor & Test

Complete guide to launch the entire Reactive RTC system, view metrics in Grafana, and generate realistic load.

---

## 📋 Prerequisites

- Docker & Docker Compose installed
- ~2GB free memory
- Ports available: `8080-8083`, `9090`, `3000`, `6379`, `9092-9096`

---

## Step 1: Launch Everything (One Command)

```bash
# Start infrastructure + services + monitoring
docker compose -f docker-compose.yml \
               -f deploy/docker-compose.prometheus.yml up -d

# Wait 30 seconds for all services to initialize
sleep 30

# Check status
docker compose ps
```

### What's Running:
```
✅ Redis          :6379
✅ Zookeeper      :2181
✅ Kafka          :9092
✅ Load-Balancer  :8081
✅ Socket-1       :8080
✅ Socket-2       :8082
✅ Prometheus     :9090
✅ Grafana        :3000
```

---

## Step 2: Verify Services

```bash
# Infrastructure
curl http://localhost:2181
curl http://localhost:9092

# Load-Balancer
curl http://localhost:8081/healthz
curl http://localhost:8081/api/v1/ring

# Socket nodes
curl http://localhost:8080/healthz
curl http://localhost:8082/healthz

# Metrics
curl http://localhost:8080/metrics | head -20
```

**Expected output:**
```json
# Ring status
{"nodes":2,"vnodes":600,"snapshot":{"nodes":[...]}}

# Health checks
OK
```

---

## Step 3: Access Grafana

### Initial Setup

**1. Open Grafana**
```
http://localhost:3000
```

**2. Login**
- Username: `admin`
- Password: `admin`

**3. Add Prometheus Data Source**
- Click **"Add your first data source"** → **Prometheus**
- URL: `http://prometheus:9090`
- Click **"Save & Test"**

**Should see:** ✅ "Data source is working"

### Create Dashboard

**4. Create New Dashboard**
- Click **"+" → "Dashboard"**
- Click **"Add visualization"**

**5. Add Panels**

#### Panel 1: Active Connections
- **Query**: `sum(rtc_socket_active_connections)`
- **Title**: "Total WebSocket Connections"
- **Visualization**: Stat

#### Panel 2: Connections Per Node
- **Query**: `rtc_socket_active_connections`
- **Title**: "Connections by Node"
- **Visualization**: Bar gauge
- **Legend**: `{{nodeId}}`

#### Panel 3: Message Rate
- **Query**: `sum(rate(rtc_socket_deliver_mps[1m]))`
- **Title**: "Messages/Second"
- **Visualization**: Stat

#### Panel 4: Message Delivery Over Time
- **Query**: `rate(rtc_socket_deliver_mps[1m])`
- **Title**: "Message Rate Over Time"
- **Visualization**: Time series
- **Legend**: `{{nodeId}} - {{type}}`

#### Panel 5: p95 Latency
- **Query**: `histogram_quantile(0.95, rate(rtc_socket_latency_bucket[1m]))`
- **Title**: "p95 Latency"
- **Visualization**: Time series

#### Panel 6: Memory Usage
- **Query**: `(jvm_memory_used / jvm_memory_max) * 100`
- **Title**: "Memory Usage %"
- **Visualization**: Time series
- **Legend**: `{{nodeId}}`

#### Panel 7: Ring Nodes
- **Query**: `rtc_lb_ring_nodes`
- **Title**: "Ring Nodes"
- **Visualization**: Stat

#### Panel 8: Handshakes
- **Query**: `sum(rate(rtc_socket_handshakes_total[1m]))`
- **Title**: "Handshakes/sec"
- **Visualization**: Time series

**6. Save Dashboard**
- Click **"Save dashboard"**
- Name: **"Reactive RTC Overview"**

---

## Step 4: Generate Load

### Option A: Quick Load Test (Simple)

```bash
# Install wscat for WebSocket testing
npm install -g wscat

# Generate 50 concurrent connections
for i in {1..50}; do
  wscat -c "ws://localhost:8080/ws?userId=user$i" &
done

# Watch in Grafana - connections should increase!
# Wait 10 seconds, then disconnect all
pkill -f wscat
```

### Option B: Advanced Load Test (Python)

**Install dependencies:**
```bash
pip install websocket-client asyncio
```

**Create `load_test.py`:**
```python
#!/usr/bin/env python3
import asyncio
import websockets
import time
import random

async def connect_user(user_id, node_port):
    uri = f"ws://localhost:{node_port}/ws?userId={user_id}"
    try:
        async with websockets.connect(uri) as ws:
            print(f"✅ Connected: {user_id}")
            
            # Send random messages
            for i in range(10):
                msg = f"message_{i}"
                await ws.send(msg)
                await asyncio.sleep(random.uniform(0.1, 0.5))
            
            # Stay connected
            await asyncio.sleep(30)
            
    except Exception as e:
        print(f"❌ Error: {user_id} - {e}")

async def main():
    # Connect 100 users split across 2 nodes
    tasks = []
    for i in range(100):
        node = 8080 if i % 2 == 0 else 8082
        tasks.append(connect_user(f"user{i}", node))
    
    await asyncio.gather(*tasks)

if __name__ == "__main__":
    print("🚀 Starting load test...")
    start = time.time()
    asyncio.run(main())
    elapsed = time.time() - start
    print(f"✅ Load test completed in {elapsed:.2f}s")
```

**Run:**
```bash
python3 load_test.py

# Watch in Grafana:
# - Connections spike to 100
# - Message rate increases
# - Latency may rise slightly
```

### Option C: Realistic Load (Shell Script)

**Create `load_test.sh`:**
```bash
#!/bin/bash

echo "🚀 Starting load test..."

# Function: Create connection and send messages
connect() {
    local user=$1
    local port=$2
    
    # Open WebSocket and send messages
    (
        for i in {1..20}; do
            echo "{\"to\":\"user$((RANDOM % 50))\",\"msg\":\"test$i\"}"
            sleep 0.5
        done
    ) | websocat "ws://localhost:$port/ws?userId=$user"
}

# Launch 50 connections on socket-1
for i in {1..50}; do
    connect "user$i" 8080 &
    sleep 0.1  # Stagger connections
done

# Launch 50 connections on socket-2
for i in {51..100}; do
    connect "user$i" 8082 &
    sleep 0.1
done

# Wait for all connections
wait

echo "✅ Load test complete"
```

**Run:**
```bash
chmod +x load_test.sh
./load_test.sh

# Watch in Grafana:
# - Total connections: 100
# - Message delivery rate spikes
# - Queue depth may increase
```

---

## Step 5: Monitor in Grafana

### Watch These Metrics

**During Load Test:**
1. **Active Connections**: Should climb to 100
2. **Message Rate**: Should spike (100-200 msg/sec)
3. **p95 Latency**: Should stay < 100ms
4. **Memory Usage**: May increase to 40-60%
5. **Handshakes/sec**: Should see spikes

**After Load Test:**
1. **Connections**: Should drop to 0
2. **Message Rate**: Should return to baseline
3. **Memory**: Should stabilize

### Alerts to Watch

**Yellow**: Memory usage > 70%  
**Red**: p95 latency > 500ms  
**Red**: Message drops > 10/sec  

---

## Step 6: Test Autoscaling

### Simulate Load Increase

```bash
# Connect 1000 users (stress test)
for i in {1..1000}; do
  node=$((i % 2 == 0 ? 8080 : 8082))
  wscat -c "ws://localhost:$node/ws?userId=user$i" &
  if [ $((i % 100)) -eq 0 ]; then
    echo "Connected $i users..."
    sleep 1
  fi
done

# Watch in Grafana:
# - Connections reach 1000
# - Message rate increases
# - Queue depth may grow
# - Heartbeats show load to load-balancer
```

### Check Scaling Decisions

```bash
# Query load-balancer metrics
curl http://localhost:8081/metrics | grep scaling

# Should see:
# rtc_lb_scaling_decisions_total{action="scale_out"} 1.0
```

---

## Step 7: Test Resilience

### Kill a Socket Node

```bash
# Stop socket-1
docker compose stop socket-1

# Watch in Grafana:
# - Connections drop by ~50%
# - Remaining connections on socket-2
# - Message rate continues

# Restart socket-1
docker compose start socket-1

# Watch:
# - Connections redistribute
# - Both nodes active
```

### Redis Reconnection

```bash
# Restart Redis
docker compose restart redis

# Watch logs:
docker compose logs -f socket-1

# Should see:
# - Reconnect attempts
# - Success after retry
# - Metrics show reconnects_total increment
```

---

## Step 8: View Detailed Metrics

### Prometheus UI

```bash
# Open Prometheus
open http://localhost:9090
```

**Try These Queries:**

**1. Current Connections:**
```promql
rtc_socket_active_connections
```

**2. Message Rate (per type):**
```promql
sum by (type) (rate(rtc_socket_deliver_mps[1m]))
```

**3. p95 Latency:**
```promql
histogram_quantile(0.95, rate(rtc_socket_latency_bucket[1m]))
```

**4. Memory Usage (MB):**
```promql
jvm_memory_used / 1024 / 1024
```

**5. Ring Health:**
```promql
rtc_lb_ring_nodes
rtc_lb_ring_vnodes
```

### Graph Tab

1. Enter any query above
2. Click **"Execute"**
3. Click **"Graph"** tab
4. See time series visualization

---

## Step 9: Load Test Scenarios

### Scenario 1: Gradual Ramp-Up

```bash
# Start with 10 users, add 10 every 5 seconds
for batch in {1..10}; do
  for i in $((batch*10-9))..$((batch*10)); do
    wscat -c "ws://localhost:8080/ws?userId=user$i" &
  done
  echo "Added batch $batch (${batch}0 users total)"
  sleep 5
done

# Watch gradual increase in Grafana
```

### Scenario 2: Spike Test

```bash
# Connect 500 users instantly
for i in {1..500}; do
  node=$((i % 2 == 0 ? 8080 : 8082))
  wscat -c "ws://localhost:$node/ws?userId=user$i" &
done

# Watch spike in connections and message rate
```

### Scenario 3: Sustained Load

```bash
# Keep 200 users connected for 5 minutes
start_load() {
  for i in {1..200}; do
    node=$((i % 2 == 0 ? 8080 : 8082))
    wscat -c "ws://localhost:$node/ws?userId=user$i" &
  done
  wait
}

# Run for 5 minutes
timeout 300 bash -c start_load
```

---

## Step 10: Export Metrics Data

### Export from Grafana

1. Select panel
2. Click **"..." → "Share"**
3. Choose **"Save to file"**
4. Export as CSV/JSON

### Export from Prometheus

```bash
# Query all metrics for last hour
curl 'http://localhost:9090/api/v1/query_range?query=rtc_socket_active_connections&start=2025-01-01T00:00:00Z&end=2025-01-01T01:00:00Z&step=15s' \
  | jq > metrics_export.json
```

---

## 🎯 Quick Reference

### Access URLs

```
Grafana:     http://localhost:3000 (admin/admin)
Prometheus:  http://localhost:9090
Socket-1:    http://localhost:8080
Socket-2:    http://localhost:8082
Load-Balancer: http://localhost:8081
```

### Key Metrics Queries

```promql
# Total connections
sum(rtc_socket_active_connections)

# Message rate
sum(rate(rtc_socket_deliver_mps[1m]))

# Latency
histogram_quantile(0.95, rate(rtc_socket_latency_bucket[1m]))

# Memory
(jvm_memory_used / jvm_memory_max) * 100
```

### Common Commands

```bash
# Start everything
docker compose -f docker-compose.yml -f deploy/docker-compose.prometheus.yml up -d

# View logs
docker compose logs -f

# Stop everything
docker compose down

# Restart service
docker compose restart socket-1

# Clean up
docker compose down -v
```

---

## 📊 Dashboard Template

**Import this JSON into Grafana:**

```json
{
  "dashboard": {
    "title": "Reactive RTC Overview",
    "panels": [
      {
        "id": 1,
        "title": "Total Connections",
        "targets": [{"expr": "sum(rtc_socket_active_connections)"}],
        "type": "stat"
      },
      {
        "id": 2,
        "title": "Connections per Node",
        "targets": [{"expr": "rtc_socket_active_connections"}],
        "type": "bargauge",
        "options": {"displayMode": "gradient"}
      },
      {
        "id": 3,
        "title": "Message Rate",
        "targets": [{"expr": "sum(rate(rtc_socket_deliver_mps[1m]))"}],
        "type": "stat"
      },
      {
        "id": 4,
        "title": "Message Rate Over Time",
        "targets": [
          {"expr": "sum by (type) (rate(rtc_socket_deliver_mps[1m]))", "legendFormat": "{{type}}"}
        ],
        "type": "timeseries"
      },
      {
        "id": 5,
        "title": "p95 Latency",
        "targets": [{"expr": "histogram_quantile(0.95, rate(rtc_socket_latency_bucket[1m]))"}],
        "type": "stat",
        "fieldConfig": {"unit": "ms"}
      },
      {
        "id": 6,
        "title": "Latency Over Time",
        "targets": [{"expr": "histogram_quantile(0.95, rate(rtc_socket_latency_bucket[1m]))"}],
        "type": "timeseries"
      },
      {
        "id": 7,
        "title": "Memory Usage",
        "targets": [{"expr": "(jvm_memory_used / jvm_memory_max) * 100", "legendFormat": "{{nodeId}}"}],
        "type": "timeseries"
      },
      {
        "id": 8,
        "title": "Ring Nodes",
        "targets": [{"expr": "rtc_lb_ring_nodes"}],
        "type": "stat"
      },
      {
        "id": 9,
        "title": "Handshake Rate",
        "targets": [{"expr": "sum(rate(rtc_socket_handshakes_total[1m]))"}],
        "type": "timeseries"
      },
      {
        "id": 10,
        "title": "Queue Depth",
        "targets": [{"expr": "rtc_socket_queue_depth"}],
        "type": "timeseries"
      }
    ]
  }
}
```

---

## 🎉 You're Ready!

Now you can:
- ✅ View real-time metrics in Grafana
- ✅ Generate load and watch system response
- ✅ Test autoscaling decisions
- ✅ Verify resilience
- ✅ Export metrics data

**Happy testing!** 🚀

