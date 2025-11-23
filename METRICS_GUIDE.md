# Metrics & Observability Guide

## Quick Start - View Metrics

### 1. Local Development

**Start the services:**
```bash
docker-compose up -d
# OR
make run-infra && make run-lb && make run-socket
```

**Access metrics endpoints:**
```bash
# Load-Balancer metrics
curl http://localhost:8081/metrics

# Socket Node 1 metrics
curl http://localhost:8080/metrics

# Socket Node 2 metrics
curl http://localhost:8082/metrics
```

**Sample output:**
```
# HELP reactive_rtc_info Build and version information
# TYPE reactive_rtc_info gauge
reactive_rtc_info{version="1.0.0",java="21"} 1

# HELP rtc_socket_active_connections Current WebSocket connections
# TYPE rtc_socket_active_connections gauge
rtc_socket_active_connections{nodeId="socket-node-1"} 42

# HELP rtc_socket_handshakes_total Total WebSocket handshakes
# TYPE rtc_socket_handshakes_total counter
rtc_socket_handshakes_total{nodeId="socket-node-1",status="success"} 150

# HELP rtc_socket_latency Message delivery latency
# TYPE rtc_socket_latency histogram
rtc_socket_latency{nodeId="socket-node-1"} 0.0035
...
```

---

## 2. Prometheus Integration

### Setup Prometheus

**Option A: Docker Compose (Recommended for local dev)**

Create `docker-compose.prometheus.yml`:
```yaml
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./deploy/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    ports:
      - "9090:9090"
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
    networks:
      - reactive-rtc-network

volumes:
  prometheus_data:

networks:
  reactive-rtc-network:
    external: true
```

**Create Prometheus config** (`deploy/prometheus.yml`):
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  # Load-Balancer
  - job_name: 'load-balancer'
    static_configs:
      - targets: ['load-balancer:8081']
        labels:
          service: 'load-balancer'
          environment: 'local'

  # Socket Nodes
  - job_name: 'socket-nodes'
    static_configs:
      - targets:
          - 'socket-1:8080'
          - 'socket-2:8082'
        labels:
          service: 'socket'
          environment: 'local'
```

**Start Prometheus:**
```bash
docker-compose -f docker-compose.yml -f docker-compose.prometheus.yml up -d
```

**Access Prometheus UI:**
```
http://localhost:9090
```

---

### Option B: Kubernetes (Production)

**ServiceMonitor for Prometheus Operator:**

Uncomment in `deploy/k8s/load-balancer-deploy.yaml`:
```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: load-balancer-metrics
  namespace: rtc
spec:
  selector:
    matchLabels:
      app: load-balancer
  endpoints:
    - port: http
      path: /metrics
      interval: 30s
```

Apply similarly for socket nodes.

---

## 3. Key Metrics Reference

### Socket Node Metrics

| Metric Name | Type | Description | Labels |
|-------------|------|-------------|--------|
| `rtc_socket_active_connections` | Gauge | Current WebSocket connections | nodeId |
| `rtc_socket_handshakes_total` | Counter | Total handshake attempts | nodeId, status |
| `rtc_socket_deliver_mps` | Counter | Messages delivered | nodeId, type |
| `rtc_socket_latency` | Timer | End-to-end message latency | nodeId |
| `rtc_socket_queue_depth` | Gauge | Outbound buffer depth | nodeId |
| `rtc_socket_reconnects_total` | Counter | Reconnect attempts | nodeId |
| `rtc_socket_resume_success_total` | Counter | Successful resumes | nodeId |
| `rtc_socket_drops_total` | Counter | Dropped messages | nodeId, reason |
| `rtc_socket_ring_version` | Gauge | Current ring version | nodeId |
| `jvm_memory_used` | Gauge | Used memory (bytes) | nodeId |
| `jvm_memory_max` | Gauge | Max memory (bytes) | nodeId |

### Load-Balancer Metrics

| Metric Name | Type | Description | Labels |
|-------------|------|-------------|--------|
| `rtc_lb_ring_nodes` | Gauge | Physical nodes in ring | - |
| `rtc_lb_ring_vnodes` | Gauge | Virtual nodes in ring | - |
| `rtc_lb_scaling_decisions_total` | Counter | Scaling decisions made | action |

---

## 4. Useful Prometheus Queries

### Connection Metrics

**Total active connections:**
```promql
sum(rtc_socket_active_connections)
```

**Connections per node:**
```promql
rtc_socket_active_connections
```

**Connection rate (new connections/sec):**
```promql
rate(rtc_socket_handshakes_total{status="success"}[5m])
```

### Throughput Metrics

**Total message rate:**
```promql
sum(rate(rtc_socket_deliver_mps[5m]))
```

**Local vs Relay delivery ratio:**
```promql
sum by (type) (rate(rtc_socket_deliver_mps[5m]))
```

### JVM Metrics

**Memory usage (percentage):**
```promql
(jvm_memory_used / jvm_memory_max) * 100
```

**Memory usage per node:**
```promql
jvm_memory_used / 1024 / 1024 / 1024
```

**Total memory across cluster:**
```promql
sum(jvm_memory_used)
```

### Latency Metrics

**p95 latency across all nodes:**
```promql
histogram_quantile(0.95, sum(rate(rtc_socket_latency_bucket[5m])) by (le))
```

**Max latency across nodes:**
```promql
max(rtc_socket_latency)
```

### Drop Rate

**Messages dropped per second:**
```promql
sum(rate(rtc_socket_drops_total[5m]))
```

**Drops by reason:**
```promql
sum by (reason) (rate(rtc_socket_drops_total[5m]))
```

### Ring Health

**Number of active nodes:**
```promql
rtc_lb_ring_nodes
```

**Ring version timeline:**
```promql
max(rtc_socket_ring_version)
```

### Scaling Activity

**Scaling decisions:**
```promql
sum by (action) (rtc_lb_scaling_decisions_total)
```

---

## 5. Grafana Dashboards

### Creating a Dashboard

**Access Grafana:**
```bash
# If using docker-compose, add Grafana:
docker run -d -p 3000:3000 --network reactive-rtc-network \
  -e GF_SECURITY_ADMIN_PASSWORD=admin \
  grafana/grafana
```

Access at: `http://localhost:3000` (admin/admin)

### Sample Dashboard Panels

#### Panel 1: Active Connections
```json
{
  "title": "Active WebSocket Connections",
  "targets": [{
    "expr": "sum(rtc_socket_active_connections)"
  }],
  "type": "graph"
}
```

**PromQL:**
```promql
sum(rtc_socket_active_connections)
```

#### Panel 2: Message Throughput
```promql
sum(rate(rtc_socket_deliver_mps[5m]))
```

#### Panel 3: p95 Latency
```promql
histogram_quantile(0.95, sum(rate(rtc_socket_latency_bucket[5m])) by (le))
```

#### Panel 4: Connection Distribution
```promql
rtc_socket_active_connections
```
- Visualization: Bar gauge
- Legend: `{{nodeId}}`

#### Panel 5: Drop Rate
```promql
sum by (reason) (rate(rtc_socket_drops_total[5m]))
```

#### Panel 6: Ring Health
```promql
rtc_lb_ring_nodes
```

---

## 6. Alerting Rules

### Sample Prometheus Alerts

**Create `deploy/alerts.yml`:**
```yaml
groups:
  - name: reactive-rtc
    interval: 30s
    rules:
      - alert: HighLatency
        expr: histogram_quantile(0.95, rate(rtc_socket_latency_bucket[5m])) > 0.5
        for: 5m
        labels:
          severity: warning
          component: socket
        annotations:
          summary: "High p95 latency detected"
          description: "p95 latency is {{ $value }}s (threshold: 500ms)"

      - alert: HighDropRate
        expr: sum(rate(rtc_socket_drops_total[5m])) > 10
        for: 2m
        labels:
          severity: critical
          component: socket
        annotations:
          summary: "Messages being dropped"
          description: "Drop rate: {{ $value }} msg/sec"

      - alert: NoActiveNodes
        expr: rtc_lb_ring_nodes == 0
        for: 1m
        labels:
          severity: critical
          component: load-balancer
        annotations:
          summary: "No socket nodes in ring"
          description: "Load-balancer has no active nodes"

      - alert: HighConnectionLoad
        expr: sum(rtc_socket_active_connections) > 40000
        for: 5m
        labels:
          severity: warning
          component: socket
        annotations:
          summary: "High connection count"
          description: "Total connections: {{ $value }} (consider scaling)"

      - alert: NodeDown
        expr: up{job="socket-nodes"} == 0
        for: 1m
        labels:
          severity: critical
          component: socket
        annotations:
          summary: "Socket node is down"
          description: "Node {{ $labels.instance }} is not responding"
```

---

## 7. Metrics Endpoints

### Direct HTTP Access

**Load-Balancer:**
```bash
curl http://localhost:8081/metrics | head -50

# Pretty print
curl -s http://localhost:8081/metrics | grep -E "^rtc_" | head -20
```

**Socket Node:**
```bash
curl http://localhost:8080/metrics | head -50

# Filter specific metrics
curl -s http://localhost:8080/metrics | grep "rtc_socket_active_connections"
```

### Kubernetes Port-Forward

```bash
# Forward load-balancer metrics
kubectl port-forward -n rtc svc/load-balancer-service 8081:8081

# Forward socket metrics
kubectl port-forward -n rtc svc/socket-service 8080:8080

# Then access
curl http://localhost:8081/metrics
```

---

## 8. Viewing Metrics in Real-Time

### Using Prometheus UI

1. **Navigate to Prometheus**: http://localhost:9090

2. **Graph Tab** - Enter queries:
   ```
   rtc_socket_active_connections
   ```

3. **Execute** - See current values

4. **Graph view** - See time series

### Sample Queries to Try

**Connection trends:**
```promql
rtc_socket_active_connections[1h]
```

**Handshake success rate:**
```promql
rate(rtc_socket_handshakes_total{status="success"}[5m])
```

**Error rate:**
```promql
sum(rate(rtc_socket_handshakes_total{status="failure"}[5m]))
```

---

## 9. Testing Metrics

### Generate Load

**Connect multiple clients:**
```bash
for i in {1..100}; do
  wscat -c "ws://localhost:8080/ws?userId=user$i" &
done
```

**Watch metrics change:**
```bash
watch -n 1 'curl -s http://localhost:8080/metrics | grep active_connections'
```

**Send heartbeat:**
```bash
curl -X POST http://localhost:8081/api/v1/nodes/heartbeat \
  -H "Content-Type: application/json" \
  -d '{
    "nodeId": "socket-node-1",
    "activeConn": 42,
    "mps": 150.5,
    "p95LatencyMs": 25.3,
    "queueDepth": 5,
    "cpu": 0.35,
    "mem": 0.45,
    "ts": '$(date +%s000)'
  }'

# Watch ring metrics
curl -s http://localhost:8081/metrics | grep "rtc_lb"
```

---

## 10. Grafana Setup (Quick)

### One-Command Grafana

```bash
docker run -d \
  --name=grafana \
  --network=reactive-rtc-network \
  -p 3000:3000 \
  -e "GF_SECURITY_ADMIN_PASSWORD=admin" \
  -e "GF_INSTALL_PLUGINS=grafana-piechart-panel" \
  grafana/grafana

# Access: http://localhost:3000 (admin/admin)
```

### Add Prometheus Data Source

1. **Grafana UI** → Configuration → Data Sources
2. **Add data source** → Prometheus
3. **URL**: `http://prometheus:9090` (if in same network)
4. **Save & Test**

### Import Dashboard

Create dashboard with panels:

**Row 1: Overview**
- Total Connections (stat panel)
- Messages/sec (stat panel)
- p95 Latency (stat panel)
- Drop Rate (stat panel)

**Row 2: Connections**
- Connections over time (graph)
- Connections per node (bar gauge)
- Handshake rate (graph)

**Row 3: Performance**
- p95 Latency over time (graph)
- Message throughput (graph)
- Local vs Relay ratio (pie chart)

**Row 4: Health**
- Ring nodes count (stat)
- Queue depth (graph)
- Drop rate by reason (graph)

---

## 11. Monitoring Checklist

### Production Metrics Checklist

- [ ] **Prometheus scraping** socket nodes every 15s
- [ ] **Prometheus scraping** load-balancer every 15s
- [ ] **Grafana dashboards** created for key metrics
- [ ] **Alerts configured** for:
  - [ ] High latency (> 500ms p95)
  - [ ] High drop rate (> 10 msg/sec)
  - [ ] No active nodes
  - [ ] Node down
  - [ ] High connection count
- [ ] **Retention configured** (30 days recommended)
- [ ] **Exporters** for system metrics (node_exporter)

---

## 12. Automated Heartbeats

### How It Works

When `LOAD_BALANCER_URL` is set, socket nodes automatically:
1. Collect metrics every 30 seconds (configurable)
2. Build Heartbeat message with real-time data
3. POST to `/api/v1/nodes/heartbeat`
4. Load-balancer updates ring and computes scaling

### Enable Automated Heartbeats

**Docker Compose** (already configured):
```yaml
environment:
  LOAD_BALANCER_URL: http://load-balancer:8081
```

**Kubernetes** (add to socket deployment):
```yaml
env:
  - name: LOAD_BALANCER_URL
    value: "http://load-balancer-service.rtc.svc.cluster.local:8081"
```

**Local run:**
```bash
export LOAD_BALANCER_URL=http://localhost:8081
java -jar socket/target/socket-1.0-SNAPSHOT.jar
```

### Verify Heartbeats Working

**Check socket logs:**
```
INFO  HeartbeatScheduler - Heartbeat scheduler started (reporting to http://load-balancer:8081)
DEBUG HeartbeatScheduler - Heartbeat sent successfully
```

**Check load-balancer logs:**
```
INFO  RingManager - Node joined: socket-node-1
INFO  HttpServer - Heartbeat received from socket-node-1
```

**Query load-balancer:**
```bash
curl http://localhost:8081/api/v1/ring | jq '.vnodes | length'
# Should show vnodes from registered nodes
```

---

## 13. Metric Cardinality

### Current Metrics Count

**Socket node**: ~15 unique metric names
**Load-balancer**: ~5 unique metric names

**With 10 nodes**:
- Socket metrics: ~150 time series
- LB metrics: ~10 time series
- **Total**: ~160 time series (low cardinality ✅)

### Best Practices

✅ **Do:**
- Use tags for dimensions (nodeId, status, type)
- Keep tag values bounded (don't use userId as tag!)
- Use histograms for latency
- Use counters for rates

❌ **Don't:**
- Use high-cardinality tags (userId, msgId, etc.)
- Create metrics per user
- Use unbounded tag values

---

## 14. Troubleshooting Metrics

### Metrics not appearing?

**Check endpoint:**
```bash
curl http://localhost:8080/metrics
# Should return Prometheus text format
```

**Check Prometheus targets:**
- Navigate to `http://localhost:9090/targets`
- Verify targets are "UP"
- Check last scrape time

**Check logs:**
```bash
docker-compose logs socket-1 | grep -i metrics
docker-compose logs load-balancer | grep -i metrics
```

### High cardinality warning?

- Review tags usage
- Ensure userId/msgId not used as tags
- Check Prometheus UI → Status → TSDB Status

### Metrics delayed?

- Check scrape interval (default 15s)
- Verify time synchronization
- Check network latency

---

## 15. Quick Dashboard Template

### JSON for Grafana Import

```json
{
  "dashboard": {
    "title": "Reactive RTC Overview",
    "panels": [
      {
        "id": 1,
        "title": "Active Connections",
        "targets": [{"expr": "sum(rtc_socket_active_connections)"}],
        "type": "stat"
      },
      {
        "id": 2,
        "title": "Message Rate",
        "targets": [{"expr": "sum(rate(rtc_socket_deliver_mps[5m]))"}],
        "type": "stat"
      },
      {
        "id": 3,
        "title": "p95 Latency",
        "targets": [{"expr": "histogram_quantile(0.95, rate(rtc_socket_latency_bucket[5m]))"}],
        "type": "stat"
      },
      {
        "id": 4,
        "title": "Connections Over Time",
        "targets": [{"expr": "rtc_socket_active_connections", "legendFormat": "{{nodeId}}"}],
        "type": "graph"
      }
    ]
  }
}
```

Save as `deploy/grafana-dashboard.json` and import via Grafana UI.

---

## 16. Production Monitoring Setup

### Complete Monitoring Stack

```bash
# 1. Start services
docker-compose up -d

# 2. Start Prometheus
docker-compose -f docker-compose.prometheus.yml up -d

# 3. Start Grafana
docker run -d --name grafana --network reactive-rtc-network \
  -p 3000:3000 grafana/grafana

# 4. Configure Grafana
# - Add Prometheus data source (http://prometheus:9090)
# - Import dashboard
# - Set up alerts
```

### Verify Entire Stack

```bash
# Check all services
docker ps | grep -E "reactive-rtc|prometheus|grafana"

# Check metrics flowing
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[].health'
# All should be "up"

# Access dashboards
open http://localhost:3000
```

---

## Summary

**To view metrics:**

1. **Direct** → `curl http://localhost:8080/metrics`
2. **Prometheus** → `http://localhost:9090` → Graph tab
3. **Grafana** → `http://localhost:3000` → Dashboards

**Automated heartbeats** send real-time metrics to load-balancer every 30 seconds when `LOAD_BALANCER_URL` is configured.

**All metrics** use Micrometer instrumentation and export in standard Prometheus text format.

**Ready for production monitoring!** 📊

