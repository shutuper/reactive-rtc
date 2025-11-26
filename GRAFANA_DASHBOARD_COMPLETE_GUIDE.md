# Grafana Dashboard - Complete Guide

Complete reference for the **Reactive RTC Monitoring Dashboard** with all metrics and panels.

## Dashboard Import

```bash
# 1. Access Grafana
open http://localhost:3000  # admin/admin

# 2. Import Dashboard
# Navigate to: Dashboards → Import
# Upload: deploy/grafana-dashboard-complete.json
# Or paste the JSON content directly
```

## Dashboard Structure

The dashboard has **11 sections** with **40+ panels**:

---

## 📊 Section 1: HTTP Server - Connections & Streams

**Panels:** 3

### Panel 1: HTTP Server - Active Connections
- **Metric**: `reactor_netty_http_server_connections_active`
- **Shows**: Active HTTP connections from reactor-netty
- **Calcs**: Last, Max

### Panel 2: HTTP Server - Total Connections
- **Metric**: `reactor_netty_http_server_connections`
- **Shows**: Total HTTP connections over time
- **Calcs**: Last, Max

### Panel 3: HTTP Server - Active HTTP/2 Streams
- **Metric**: `reactor_netty_http_server_streams_active`
- **Shows**: Active HTTP/2 streams
- **Calcs**: Last, Max

---

## 📤 Section 2: HTTP Server - Data Transfer

**Panels:** 2

### Panel 4: HTTP Server - Data Received (bytes/s)
- **Metric**: `rate(reactor_netty_http_server_data_received_bytes_sum[1m])`
- **Shows**: Inbound HTTP traffic rate
- **Calcs**: Mean, Max

### Panel 5: HTTP Server - Data Sent (bytes/s)
- **Metric**: `rate(reactor_netty_http_server_data_sent_bytes_sum[1m])`
- **Shows**: Outbound HTTP traffic rate
- **Calcs**: Mean, Max

---

## ⚡ Section 3: HTTP Server - Latency & Performance

**Panels:** 3

### Panel 6: HTTP Server - Response Time
- **Metrics**: Average and Max HTTP response times
- **Thresholds**: Yellow at 0.1s, Red at 0.5s
- **Calcs**: Mean, p95, Max

### Panel 7: HTTP Server - Data Received Time
- **Metric**: Time spent receiving data
- **Calcs**: Mean, Max

### Panel 8: HTTP Server - Data Sent Time
- **Metric**: Time spent sending data
- **Calcs**: Mean, Max

---

## 🔴 Section 4: HTTP Server - Errors

**Panels:** 1

### Panel 9: HTTP Server - Error Rate
- **Metric**: `rate(reactor_netty_http_server_errors_total[1m])`
- **Shows**: HTTP errors per second
- **Alert**: Turns red at 1 error/s
- **Calcs**: Last, Sum

---

## 💾 Section 5: JVM - Memory

**Panels:** 2

### Panel 10: JVM - Heap Memory
- **Metrics**: 
  - `jvm_memory_used_bytes{area="heap"}` - Used heap
  - `jvm_memory_max_bytes{area="heap"}` - Max heap
- **Shows**: Heap memory usage vs capacity
- **Calcs**: Last, Max

### Panel 11: JVM - Non-Heap Memory
- **Metric**: `jvm_memory_used_bytes{area="nonheap"}`
- **Shows**: Non-heap memory (metaspace, code cache, etc.)
- **Calcs**: Last, Max

---

## 🖥️ Section 6: JVM - CPU & Threads

**Panels:** 2

### Panel 12: CPU Usage
- **Metrics**:
  - `process_cpu_usage` - Process CPU usage
  - `system_cpu_usage` - System-wide CPU usage
- **Thresholds**: Yellow at 70%, Red at 90%
- **Calcs**: Mean, Max

### Panel 13: JVM - Thread States
- **Metric**: `jvm_threads_states_threads`
- **Shows**: Thread states (runnable, waiting, blocked, etc.)
- **Stacking**: Normal (stacked)
- **Calcs**: Last

---

## 🔌 Section 7: Application - WebSocket Metrics

**Panels:** 3

### Panel 14: Queue Depth
- **Metric**: `rtc_socket_queue_depth`
- **Shows**: Outbound message queue depth per node
- **Purpose**: Monitor backpressure
- **Calcs**: Last, Max

### Panel 15: Handshakes Rate
- **Metrics**:
  - `rtc_socket_handshakes_total{status="success"}` - Successful handshakes
  - `rtc_socket_handshakes_total{status="failure"}` - Failed handshakes
- **Stacking**: Normal
- **Calcs**: Last

### Panel 16: Message Delivery Rate (MPS)
- **Metrics**:
  - `rtc_socket_deliver_mps_total{type="local"}` - Local delivery rate
  - `rtc_socket_deliver_mps_total{type="relay"}` - Relay delivery rate
- **Shows**: Messages per second throughput
- **Stacking**: Normal
- **Calcs**: Last

---

## 🔄 Section 8: Application - Resume & Reconnects

**Panels:** 3

### Panel 17: Reconnect Rate
- **Metric**: `rate(rtc_socket_reconnects_total[1m])`
- **Shows**: Client reconnection rate
- **Calcs**: Last, Sum

### Panel 18: Resume Rate
- **Metrics**:
  - `rtc_socket_resume_success_total` - Successful resumes
  - `rtc_socket_resume_failure_total` - Failed resumes
- **Stacking**: Normal
- **Calcs**: Last

### Panel 19: Message Drops Rate
- **Metrics**:
  - `rtc_socket_drops_total{reason="buffer_full"}` - Dropped due to buffer
  - `rtc_socket_drops_total{reason="rate_limit"}` - Dropped due to rate limit
- **Alert**: Red threshold at 1 drop/s
- **Stacking**: Normal
- **Calcs**: Last, Sum

---

## 🎯 Section 9: Application - Latency Metrics

**Panels:** 3

### Panel 20: End-to-End Message Delivery Latency ⭐
- **Metrics**:
  - `histogram_quantile(0.95, rate(rtc_socket_latency_seconds_bucket[5m]))` - **p95**
  - `histogram_quantile(0.99, rate(rtc_socket_latency_seconds_bucket[5m]))` - **p99**
  - `histogram_quantile(0.50, rate(rtc_socket_latency_seconds_bucket[5m]))` - **p50**
  - `vector(0.5)` - **SLO line at 500ms**
- **Critical**: Red dashed line shows 500ms SLO
- **Thresholds**: Yellow at 200ms, Red at 500ms
- **Calcs**: Mean, Last, Max

### Panel 21: Kafka Publish Latency
- **Metrics**: p50, p95, p99 Kafka publish latencies
- **Shows**: Time to publish to Kafka broker
- **Per**: Topic
- **Calcs**: Mean, Last, Max

### Panel 24: Current p95 Latency (SLO: 500ms) - GAUGE ⭐
- **Metric**: `histogram_quantile(0.95, ...) * 1000` (in milliseconds)
- **Type**: Gauge
- **Thresholds**: 
  - Green: < 200ms
  - Yellow: 200-500ms
  - Red: > 500ms
- **Purpose**: At-a-glance SLO compliance

---

## 📨 Section 10: Kafka - Consumer Lag & Topics

**Panels:** 9 (from kafka_exporter)

### Panel 25: Kafka Consumer Lag (Socket Nodes) ⭐
- **Metric**: `kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"}`
- **Shows**: Total lag per consumer group
- **Thresholds**: 
  - Green: < 500 messages
  - Yellow: 500-1000 messages
  - Red: > 1000 messages
- **Critical**: Monitor this for backlog detection
- **Calcs**: Last, Max, Mean

### Panel 26: Kafka Consumer Lag per Partition
- **Metric**: `kafka_consumergroup_lag{consumergroup=~"socket-delivery-.*"}`
- **Shows**: Lag broken down by partition
- **Purpose**: Identify hot partitions
- **Calcs**: Last, Max

### Panel 27: Total Consumer Lag (All Nodes) - GAUGE ⭐
- **Metric**: `sum(kafka_consumergroup_lag_sum{...})`
- **Type**: Gauge
- **Thresholds**: Yellow at 500, Red at 1000
- **Purpose**: Quick health check

### Panel 28: Active Consumer Group Members - GAUGE
- **Metric**: `sum(kafka_consumergroup_members{...})`
- **Shows**: Number of active consumers
- **Alert**: Red if 0 (no consumers)
- **Expected**: Should match socket node count

### Panel 29: Consumer Offset Rate (messages/sec)
- **Metric**: `rate(kafka_consumergroup_current_offset_sum[1m])`
- **Shows**: How fast consumers are processing
- **Compare with**: Production rate
- **Calcs**: Last, Mean

### Panel 30: Kafka Topic Partitions
- **Metric**: `kafka_topic_partitions{topic=~"delivery_node_.*"}`
- **Shows**: Number of partitions per topic
- **Calcs**: Last

### Panel 31: Kafka Topic Production Rate (messages/sec)
- **Metric**: `rate(kafka_topic_partition_current_offset[1m])`
- **Shows**: Messages being produced to topics
- **Per**: Topic and partition
- **Calcs**: Last, Mean

### Panel 32: Under-Replicated Partitions (should be 0)
- **Metric**: `kafka_topic_partition_under_replicated_partition{...}`
- **Shows**: Partitions not fully replicated
- **Alert**: Red threshold at 1
- **Expected**: Should always be 0
- **Calcs**: Last, Max

### Panel 33: In-Sync Replicas per Partition
- **Metric**: `kafka_topic_partition_in_sync_replica{...}`
- **Shows**: Number of in-sync replicas
- **Expected**: Should equal replication factor
- **Calcs**: Last

---

## 🌐 Section 11: Network - Traffic & Bandwidth ⭐ NEW

**Panels:** 7

### Panel 34: Inbound Network Traffic (bytes/sec)
- **Metrics**:
  - `rate(rtc_socket_network_inbound_ws_bytes_total[1m])` - WebSocket (Green)
  - `rate(rtc_socket_network_inbound_kafka_bytes_total[1m])` - Kafka (Blue)
- **Type**: Stacked area chart
- **Shows**: Inbound bandwidth by source
- **Per**: Node
- **Calcs**: Mean, Last, Max

### Panel 35: Outbound Network Traffic (bytes/sec)
- **Metrics**:
  - `rate(rtc_socket_network_outbound_ws_bytes_total[1m])` - WebSocket (Orange)
  - `rate(rtc_socket_network_outbound_kafka_bytes_total[1m])` - Kafka (Blue)
- **Type**: Stacked area chart
- **Shows**: Outbound bandwidth by destination
- **Per**: Node
- **Calcs**: Mean, Last, Max

### Panel 36: Total Inbound Traffic Rate - GAUGE
- **Metric**: `sum(rate(rtc_socket_network_inbound_ws_bytes_total[1m]) + rate(rtc_socket_network_inbound_kafka_bytes_total[1m]))`
- **Type**: Gauge
- **Unit**: bytes/sec
- **Thresholds**:
  - Green: < 10 MB/s
  - Yellow: 10-50 MB/s
  - Red: > 50 MB/s

### Panel 37: Total Outbound Traffic Rate - GAUGE
- **Metric**: `sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m]) + rate(rtc_socket_network_outbound_kafka_bytes_total[1m]))`
- **Type**: Gauge
- **Unit**: bytes/sec
- **Thresholds**: Same as Panel 36

### Panel 38: Inbound Message Size (Average & Max)
- **Metrics**:
  - `rate(rtc_socket_message_size_inbound_sum[1m]) / rate(rtc_socket_message_size_inbound_count[1m])` - Average
  - `rtc_socket_message_size_inbound_max` - Maximum
- **Shows**: Message size statistics
- **Purpose**: Identify large messages
- **Calcs**: Mean, Last, Max

### Panel 39: Outbound Message Size (Average & Max)
- **Metrics**:
  - `rate(rtc_socket_message_size_outbound_sum[1m]) / rate(rtc_socket_message_size_outbound_count[1m])` - Average
  - `rtc_socket_message_size_outbound_max` - Maximum
- **Shows**: Outbound message sizes
- **Calcs**: Mean, Last, Max

### Panel 40: Total Network Bandwidth per Node (MB/sec)
- **Metrics**:
  - Combined inbound per node (in MB/sec)
  - Combined outbound per node (in MB/sec)
- **Shows**: Total bandwidth consumption per node
- **Purpose**: Identify hot nodes
- **Calcs**: Mean, Last

---

## 🎯 Key Performance Indicators (KPIs)

### Critical Gauges to Monitor:

| Gauge | Metric | Green | Yellow | Red | Panel |
|-------|--------|-------|--------|-----|-------|
| **p95 Latency** | `rtc_socket_latency` | < 200ms | 200-500ms | > 500ms | #24 |
| **Consumer Lag** | `kafka_consumergroup_lag_sum` | < 500 | 500-1000 | > 1000 | #27 |
| **Inbound Traffic** | Network bytes/sec | < 10MB/s | 10-50MB/s | > 50MB/s | #36 |
| **Outbound Traffic** | Network bytes/sec | < 10MB/s | 10-50MB/s | > 50MB/s | #37 |
| **Active Consumers** | `kafka_consumergroup_members` | ≥ 1 | - | 0 | #28 |

---

## 📈 Metrics Coverage

### Application Metrics (Your Code)

| Category | Metrics | Panels |
|----------|---------|--------|
| **Throughput** | `rtc_socket_deliver_mps_total` | #16 |
| **Latency** | `rtc_socket_latency_seconds_bucket` | #20, #24 |
| **Kafka Publish** | `rtc_kafka_publish_latency_seconds_bucket` | #21 |
| **Redis** | `rtc_redis_latency_seconds_bucket` | #22 |
| **Queue Depth** | `rtc_socket_queue_depth` | #14 |
| **Handshakes** | `rtc_socket_handshakes_total` | #15 |
| **Reconnects** | `rtc_socket_reconnects_total` | #17 |
| **Resumes** | `rtc_socket_resume_{success,failure}_total` | #18 |
| **Drops** | `rtc_socket_drops_total` | #19 |
| **Network Traffic** | `rtc_socket_network_{inbound,outbound}_{ws,kafka}_bytes_total` | #34, #35, #36, #37 |
| **Message Sizes** | `rtc_socket_message_size_{inbound,outbound}` | #38, #39 |
| **Bandwidth** | Combined network metrics | #40 |

### Infrastructure Metrics

| Source | Metrics | Panels |
|--------|---------|--------|
| **Reactor Netty** | HTTP connections, streams, data transfer | #1-9 |
| **JVM** | Memory, CPU, threads | #10-13 |
| **Kafka Exporter** | Consumer lag, topics, partitions | #25-33 |

---

## 🚨 Alert Conditions to Watch

### Immediate Action Required (Red)

```promql
# p95 latency exceeds SLO
histogram_quantile(0.95, rate(rtc_socket_latency_seconds_bucket[5m])) > 0.5

# Consumer lag critical
sum(kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"}) > 1000

# No active consumers
sum(kafka_consumergroup_members{consumergroup=~"socket-delivery-.*"}) == 0

# Under-replicated partitions
kafka_topic_partition_under_replicated_partition > 0

# High bandwidth
sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m])) / 1024 / 1024 > 50
```

### Warning (Yellow)

```promql
# Latency approaching SLO
histogram_quantile(0.95, rate(rtc_socket_latency_seconds_bucket[5m])) > 0.2

# Consumer lag growing
kafka_consumergroup_lag_sum{consumergroup=~"socket-delivery-.*"} > 500

# CPU high
process_cpu_usage > 0.7

# Message drops occurring
rate(rtc_socket_drops_total[1m]) > 0
```

---

## 🔍 Using the Dashboard

### Normal Operations

All gauges should be **green**:
- ✅ p95 latency < 200ms
- ✅ Consumer lag < 500 messages
- ✅ All consumer groups have active members
- ✅ No under-replicated partitions
- ✅ Network bandwidth reasonable

### Troubleshooting Scenarios

#### Scenario 1: High Latency (Panel #24 is RED)

**Steps:**
1. Check Panel #20 (End-to-End Latency) - which node?
2. Check Panel #21 (Kafka Publish Latency) - is Kafka slow?
3. Check Panel #22 (Redis Latency) - is Redis slow?
4. Check Panel #12 (CPU Usage) - is CPU saturated?
5. Check Panel #10 (Heap Memory) - is JVM under memory pressure?

**Action:**
```bash
# Scale out if system-wide issue
kubectl scale deployment socket-deployment --replicas=5 -n reactive-rtc
```

#### Scenario 2: High Consumer Lag (Panel #27 is RED)

**Steps:**
1. Check Panel #25 (Consumer Lag by Node) - which consumer?
2. Check Panel #29 (Consumer Offset Rate) - is consumer processing messages?
3. Check Panel #31 (Production Rate) - is production rate too high?
4. Check Panel #28 (Active Consumers) - are all consumers running?

**Action:**
```bash
# Check consumer logs
kubectl logs -f deployment/socket-deployment -n reactive-rtc | grep "Kafka message received"

# Scale if needed
kubectl scale deployment socket-deployment --replicas=4 -n reactive-rtc
```

#### Scenario 3: High Network Bandwidth (Panel #36/#37 RED)

**Steps:**
1. Check Panel #34/#35 (Traffic breakdown) - WebSocket or Kafka?
2. Check Panel #38/#39 (Message sizes) - are messages too large?
3. Check Panel #16 (Message rate) - is throughput too high?
4. Check Panel #40 (Per-node bandwidth) - is one node hot?

**Action:**
```bash
# Optimize message sizes
# - Implement compression
# - Reduce payload
# - Batch messages

# Or scale if needed
kubectl scale deployment socket-deployment --replicas=6 -n reactive-rtc
```

---

## 📊 Dashboard Metrics Exportrts

All metrics are exported in Prometheus format at:
```
http://socket-node:8080/metrics
http://load-balancer:8081/metrics
http://kafka-exporter:9308/metrics
```

### Test Metrics Availability

```bash
# Check application metrics
curl http://localhost:8080/metrics | grep rtc_socket

# Expected metrics:
# rtc_socket_latency_seconds_bucket{...}
# rtc_socket_deliver_mps_total{...}
# rtc_socket_network_inbound_ws_bytes_total{...}
# rtc_socket_message_size_inbound_count{...}

# Check Kafka metrics
curl http://localhost:9308/metrics | grep kafka_consumergroup

# Expected metrics:
# kafka_consumergroup_lag_sum{...}
# kafka_consumergroup_members{...}
# kafka_topic_partition_current_offset{...}
```

---

## 🎨 Dashboard Customization

### Add Your Own Panel

1. Click **"Add"** → **"Visualization"**
2. Select **Prometheus** datasource
3. Enter your PromQL query:
   ```promql
   # Example: Bandwidth per message
   sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m])) /
   sum(rate(rtc_socket_deliver_mps_total[1m]))
   ```
4. Configure visualization type and thresholds
5. Save dashboard

### Useful Custom Queries

```promql
# Average connections per node
avg(rtc_socket_active_connections)

# Local vs Relay delivery ratio
sum(rate(rtc_socket_deliver_mps_total{type="local"}[1m])) /
sum(rate(rtc_socket_deliver_mps_total{type="relay"}[1m]))

# Bandwidth per connection
sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m])) / 
sum(rtc_socket_active_connections)

# Average message size
rate(rtc_socket_message_size_outbound_sum[1m]) / 
rate(rtc_socket_message_size_outbound_count[1m])
```

---

## 📖 Related Documentation

- **[METRICS_RECORDING_SUMMARY.md](./METRICS_RECORDING_SUMMARY.md)** - Where metrics are recorded
- **[KAFKA_MONITORING_GUIDE.md](./KAFKA_MONITORING_GUIDE.md)** - Kafka metrics deep dive
- **[NETWORK_METRICS_GUIDE.md](./NETWORK_METRICS_GUIDE.md)** - Network metrics guide
- **[PROMETHEUS_QUERY_API_GUIDE.md](./PROMETHEUS_QUERY_API_GUIDE.md)** - Query metrics via API
- **[Kafka Exporter GitHub](https://github.com/danielqsj/kafka_exporter)** - kafka_exporter docs

---

## 🚀 Quick Start

```bash
# 1. Start the full stack
docker-compose -f docker-compose.yml -f deploy/docker-compose.prometheus.yml up -d

# 2. Wait for services to be healthy
docker-compose ps

# 3. Access Grafana
open http://localhost:3000

# 4. Login with admin/admin

# 5. Import dashboard
# Dashboards → Import → Upload deploy/grafana-dashboard-complete.json

# 6. View your metrics!
```

---

## ✅ Health Check Checklist

Before going to production, verify all panels show data:

- [ ] HTTP connections active (Panel #1)
- [ ] Queue depth visible (Panel #14)
- [ ] Handshakes occurring (Panel #15)
- [ ] Message delivery rate visible (Panel #16)
- [ ] **p95 latency in green zone** (Panel #24) ⭐
- [ ] Consumer lag low (Panel #27) ⭐
- [ ] Consumer members match node count (Panel #28)
- [ ] Network traffic visible (Panels #34, #35)
- [ ] No under-replicated partitions (Panel #32)

**Your monitoring dashboard is complete and production-ready! 📊**


