# Grafana Dashboard Import Guide

## Quick Import

1. **Open Grafana**: http://localhost:3000
2. **Login**: admin / admin
3. **Import Dashboard**:
   - Click "+" icon in left sidebar
   - Select "Import dashboard"
   - Click "Upload JSON file"
   - Select `deploy/grafana-dashboard.json`
   - Click "Load"
   - Select "Prometheus" as data source
   - Click "Import"

## Dashboard Panels

### 1. Active WebSocket Connections
- **Metrics**: Sum of active connections across all socket nodes
- **Formula**: `sum(rtc_connections_active{component="socket-node"})`

### 2. Message Delivery Rate (MPS)
- **Metrics**: Messages per second across all nodes
- **Formula**: `sum(rate(rtc_deliver_total[5m]))`

### 3. P95 Latency
- **Metrics**: 95th percentile latency per node
- **Thresholds**: 
  - Green: < 400ms
  - Yellow: 400-500ms
  - Red: > 500ms
- **Formula**: `histogram_quantile(0.95, sum(rate(rtc_latency_bucket[5m])) by (le, nodeId))`

### 4. CPU Usage (%)
- **Metrics**: CPU usage per socket node
- **Thresholds**:
  - Green: < 70%
  - Yellow: 70-85%
  - Orange: 85-95%
  - Red: > 95%
- **Formula**: `rtc_cpu_usage{component="socket-node"} * 100`

### 5. Memory Usage (%)
- **Metrics**: Memory usage per socket node
- **Thresholds**:
  - Green: < 75%
  - Yellow: 75-90%
  - Orange: 90-95%
  - Red: > 95%
- **Formula**: `rtc_memory_usage{component="socket-node"} * 100`

### 6. Active Socket Nodes
- **Metrics**: Count of active socket nodes
- **Formula**: `count(rtc_connections_active{component="socket-node"} > 0)`

### 7. Handshakes (5min)
- **Metrics**: Successful vs failed handshakes per node
- **Formula**: `sum(increase(rtc_handshakes_total[5m])) by (nodeId, status)`

### 8. Message Queue Depth
- **Metrics**: Number of messages waiting in queue per node
- **Formula**: `sum(rtc_queue_depth{component="socket-node"}) by (nodeId)`

## Data Source Setup

Make sure Prometheus is configured in Grafana:

1. **Go to Configuration > Data Sources**
2. **Click "Add data source"**
3. **Select "Prometheus"**
4. **Set URL**: `http://prometheus:9090`
5. **Click "Save & Test"**

## Custom Queries

You can add custom panels using these Prometheus queries:

### Total Connections Across Cluster
```promql
sum(rtc_connections_active)
```

### Average Message Rate
```promql
avg(rate(rtc_deliver_total[5m]))
```

### Connections per Node
```promql
rtc_connections_active{nodeId=~".*"}
```

### JVM Memory Usage
```promql
jvm_memory_used_bytes{application="socket-node"} / jvm_memory_max_bytes{application="socket-node"} * 100
```

### Health Status
```promql
up{job=~"socket-.*|load-balancer"}
```

## Troubleshooting

### No Data Showing

1. **Check Prometheus targets**: http://localhost:9090/targets
2. **Verify metrics endpoint**: http://localhost:8080/metrics
3. **Check Grafana time range**: Use "Last 15 minutes" or "Last 1 hour"

### Missing Metrics

1. Ensure `PrometheusMetricsExporter` is enabled
2. Check `/metrics` endpoint returns data
3. Verify service labels match queries

## Screenshots

After import, your dashboard should show:
- **Real-time WebSocket connections**
- **Message throughput**
- **P95 latency (with SLO line)**
- **CPU/Memory usage**
- **Node health status**











