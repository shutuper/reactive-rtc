# Network Traffic Metrics Guide

This guide explains how network traffic is monitored in the Reactive RTC system.

## Overview

Network traffic metrics track bandwidth usage across WebSocket and Kafka connections, helping you:
- Monitor network bandwidth consumption
- Identify bandwidth bottlenecks
- Optimize message sizes
- Plan network capacity
- Detect anomalous traffic patterns

## Metrics

### 1. Network Traffic Counters (Bytes)

#### Inbound Traffic

| Metric | Description | Tags |
|--------|-------------|------|
| `rtc.socket.network.inbound.ws.bytes` | Total bytes received from WebSocket clients | `node_id` |
| `rtc.socket.network.inbound.kafka.bytes` | Total bytes received from Kafka | `node_id` |

#### Outbound Traffic

| Metric | Description | Tags |
|--------|-------------|------|
| `rtc.socket.network.outbound.ws.bytes` | Total bytes sent to WebSocket clients | `node_id` |
| `rtc.socket.network.outbound.kafka.bytes` | Total bytes sent to Kafka broker | `node_id` |

### 2. Message Size Distribution Summaries

| Metric | Description | Tags | Available Stats |
|--------|-------------|------|----------------|
| `rtc.socket.message.size.inbound` | Inbound message size distribution | `node_id` | count, sum, max |
| `rtc.socket.message.size.outbound` | Outbound message size distribution | `node_id` | count, sum, max |

**Note:** These provide count, sum, and max statistics. Average can be calculated as `sum/count`.

## Recording Locations

### WebSocket Inbound Traffic

**File:** `WebSocketHandler.java`  
**Method:** `handleInboundMessage(String userId, String messageJson)` (line 147)

```java
// Record inbound WebSocket traffic
metricsService.recordNetworkInboundWs(messageJson.getBytes(StandardCharsets.UTF_8).length);
```

**Triggers:** Every time a client sends a message via WebSocket

### WebSocket Outbound Traffic

**File:** `WebSocketHandler.java`  
**Method:** `sendOutboundMessages(String clientId, int resumeOffset, Session session)` (line 119)

```java
.doOnNext(message -> {
    // Record outbound WebSocket traffic
    metricsService.recordNetworkOutboundWs(message.getBytes(StandardCharsets.UTF_8).length);
})
```

**Triggers:** Every time a message is sent to a WebSocket client (welcome, buffered, or live messages)

### Kafka Inbound Traffic

**File:** `KafkaService.java`  
**Method:** `listenToDeliveryMessages(String currentNodeId)` (line 190)

```java
// Record inbound Kafka traffic
String messageValue = record.value();
metricsService.recordNetworkInboundKafka(messageValue.getBytes(StandardCharsets.UTF_8).length);
```

**Triggers:** Every time a message is consumed from Kafka delivery topics

### Kafka Outbound Traffic

**File:** `KafkaService.java`  
**Method:** `publishRelay(String targetNodeIdHint, Envelope envelope)` (line 281)

```java
String json = JsonUtils.writeValueAsString(envelope);

// Record outbound Kafka traffic
metricsService.recordNetworkOutboundKafka(json.getBytes(StandardCharsets.UTF_8).length);
```

**Triggers:** Every time a message is published to Kafka for relay

## Prometheus Queries

### Bandwidth Usage (bytes/sec)

```promql
# Inbound WebSocket traffic rate
rate(rtc_socket_network_inbound_ws_bytes_total[1m])

# Inbound Kafka traffic rate
rate(rtc_socket_network_inbound_kafka_bytes_total[1m])

# Outbound WebSocket traffic rate
rate(rtc_socket_network_outbound_ws_bytes_total[1m])

# Outbound Kafka traffic rate
rate(rtc_socket_network_outbound_kafka_bytes_total[1m])
```

### Total Bandwidth per Node (MB/sec)

```promql
# Total inbound per node
sum by (node_id) (
  rate(rtc_socket_network_inbound_ws_bytes_total[1m]) + 
  rate(rtc_socket_network_inbound_kafka_bytes_total[1m])
) / 1024 / 1024

# Total outbound per node
sum by (node_id) (
  rate(rtc_socket_network_outbound_ws_bytes_total[1m]) + 
  rate(rtc_socket_network_outbound_kafka_bytes_total[1m])
) / 1024 / 1024
```

### Aggregate Bandwidth (All Nodes)

```promql
# Total inbound across all nodes (MB/sec)
sum(
  rate(rtc_socket_network_inbound_ws_bytes_total[1m]) + 
  rate(rtc_socket_network_inbound_kafka_bytes_total[1m])
) / 1024 / 1024

# Total outbound across all nodes (MB/sec)
sum(
  rate(rtc_socket_network_outbound_ws_bytes_total[1m]) + 
  rate(rtc_socket_network_outbound_kafka_bytes_total[1m])
) / 1024 / 1024
```

### Message Size Statistics

```promql
# Average inbound message size (bytes)
rate(rtc_socket_message_size_inbound_sum{job="socket-nodes"}[1m]) / 
rate(rtc_socket_message_size_inbound_count{job="socket-nodes"}[1m])

# Max inbound message size (bytes)
rtc_socket_message_size_inbound_max{job="socket-nodes"}

# Average outbound message size (bytes)
rate(rtc_socket_message_size_outbound_sum{job="socket-nodes"}[1m]) / 
rate(rtc_socket_message_size_outbound_count{job="socket-nodes"}[1m])

# Max outbound message size (bytes)
rtc_socket_message_size_outbound_max{job="socket-nodes"}

# Total message count
rate(rtc_socket_message_size_inbound_count{job="socket-nodes"}[1m])
```

### Traffic Breakdown

```promql
# WebSocket vs Kafka inbound ratio
sum(rate(rtc_socket_network_inbound_ws_bytes_total[1m])) / 
sum(rate(rtc_socket_network_inbound_kafka_bytes_total[1m]))

# WebSocket vs Kafka outbound ratio
sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m])) / 
sum(rate(rtc_socket_network_outbound_kafka_bytes_total[1m]))
```

## Grafana Dashboard Panels

The dashboard now includes a **"Network - Traffic & Bandwidth"** section with:

### Panel 1: Inbound Network Traffic (bytes/sec)
- **Stacked area chart** showing WebSocket and Kafka inbound traffic
- Color-coded: Green for WebSocket, Blue for Kafka
- Per-node breakdown

### Panel 2: Outbound Network Traffic (bytes/sec)
- **Stacked area chart** showing WebSocket and Kafka outbound traffic
- Color-coded: Orange for WebSocket, Blue for Kafka
- Per-node breakdown

### Panel 3: Total Inbound Traffic Rate Gauge
- **Gauge** showing aggregate inbound bandwidth
- Thresholds:
  - Green: < 10 MB/sec
  - Yellow: 10-50 MB/sec
  - Red: > 50 MB/sec

### Panel 4: Total Outbound Traffic Rate Gauge
- **Gauge** showing aggregate outbound bandwidth
- Same thresholds as inbound

### Panel 5: Inbound Message Size (Average & Max)
- **Line chart** showing average and max message sizes
- Helps identify message size patterns and outliers
- Per-node breakdown

### Panel 6: Outbound Message Size (Average & Max)
- **Line chart** showing average and max message sizes
- Per-node breakdown

### Panel 7: Total Network Bandwidth per Node (MB/sec)
- **Combined view** showing total inbound + outbound per node
- Useful for identifying hot nodes
- Shows in MB/sec for easier reading

## Use Cases

### 1. Network Capacity Planning

```promql
# Max bandwidth over 24 hours (GB)
(
  max_over_time(rate(rtc_socket_network_inbound_ws_bytes_total[1m])[24h:]) +
  max_over_time(rate(rtc_socket_network_outbound_ws_bytes_total[1m])[24h:])
) * 3600 * 24 / 1024 / 1024 / 1024
```

### 2. Detect Bandwidth Spikes

```promql
# Alert when bandwidth exceeds 50 MB/sec
(
  sum(rate(rtc_socket_network_inbound_ws_bytes_total[1m])) +
  sum(rate(rtc_socket_network_inbound_kafka_bytes_total[1m]))
) / 1024 / 1024 > 50
```

### 3. Optimize Message Sizes

```promql
# Average message size
rate(rtc_socket_message_size_inbound_sum[5m]) / rate(rtc_socket_message_size_inbound_count[5m])

# If average is > 10KB, consider:
# - Message compression
# - Payload optimization
# - Batching strategy
```

### 4. Traffic Pattern Analysis

```promql
# Ratio of local vs relay delivery (network efficiency)
sum(rate(rtc_socket_network_outbound_kafka_bytes_total[1m])) /
sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m]))

# High ratio = lots of cross-node traffic
# Low ratio = efficient local routing
```

## Alerting Rules

```yaml
groups:
- name: network_alerts
  interval: 30s
  rules:
  
  # High bandwidth usage
  - alert: NetworkBandwidthHigh
    expr: |
      (
        sum(rate(rtc_socket_network_inbound_ws_bytes_total[1m])) +
        sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m]))
      ) / 1024 / 1024 > 100
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "High network bandwidth usage"
      description: "Network bandwidth exceeds 100 MB/sec for 5 minutes"

  # Large message sizes
  - alert: LargeMessageSizes
    expr: |
      rate(rtc_socket_message_size_inbound_sum[5m]) / 
      rate(rtc_socket_message_size_inbound_count[5m]) > 102400
    for: 10m
    labels:
      severity: warning
    annotations:
      summary: "Large message sizes detected"
      description: "Average message size exceeds 100KB, consider optimization"
  
  # Extremely large individual messages
  - alert: ExtremeMessageSize
    expr: |
      rtc_socket_message_size_inbound_max > 1048576
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Extremely large message detected"
      description: "Max message size exceeds 1MB on node {{$labels.node_id}}"

  # Unbalanced traffic
  - alert: UnbalancedNodeTraffic
    expr: |
      max by (node_id) (
        rate(rtc_socket_network_outbound_ws_bytes_total[1m])
      ) / avg(
        rate(rtc_socket_network_outbound_ws_bytes_total[1m])
      ) > 2
    for: 10m
    labels:
      severity: warning
    annotations:
      summary: "Unbalanced traffic distribution"
      description: "Node {{$labels.node_id}} handling 2x average traffic"
```

## Capacity Planning

### Estimate Network Requirements

Based on your metrics:

```promql
# Daily data transfer (GB/day)
(
  increase(rtc_socket_network_inbound_ws_bytes_total[24h]) +
  increase(rtc_socket_network_outbound_ws_bytes_total[24h])
) / 1024 / 1024 / 1024

# Monthly estimate (TB/month)
(
  increase(rtc_socket_network_inbound_ws_bytes_total[24h]) +
  increase(rtc_socket_network_outbound_ws_bytes_total[24h])
) * 30 / 1024 / 1024 / 1024 / 1024
```

### Bandwidth per Connection

```promql
# Average bandwidth per connection (bytes/sec)
(
  sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m]))
) / sum(rtc_socket_active_connections)
```

### Bandwidth per Message

```promql
# Average bandwidth per delivered message (bytes)
sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m])) /
sum(rate(rtc_socket_deliver_mps_total[1m]))
```

## Optimization Tips

### If Bandwidth is High:

1. **Enable compression** for WebSocket messages
2. **Optimize payload** - remove unnecessary fields
3. **Batch small messages** instead of sending individually
4. **Use message deduplication** to avoid sending duplicates
5. **Implement differential updates** instead of full state

### If Message Sizes are Large:

```promql
# Check average message size
rate(rtc_socket_message_size_outbound_sum[1m]) / 
rate(rtc_socket_message_size_outbound_count[1m])

# Check max message size
rtc_socket_message_size_outbound_max

# If average > 10KB or max > 100KB:
# - Review JSON structure
# - Use protobuf or msgpack instead of JSON
# - Implement compression (gzip, zstd)
# - Split large messages into chunks
```

## Cost Estimation

### Cloud Network Egress Costs

Typical cloud pricing (approximate):
- **AWS:** $0.09/GB egress
- **GCP:** $0.12/GB egress
- **Azure:** $0.087/GB egress

**Monthly cost estimation:**
```promql
# Monthly outbound data (GB)
(
  sum(increase(rtc_socket_network_outbound_ws_bytes_total[30d])) +
  sum(increase(rtc_socket_network_outbound_kafka_bytes_total[30d]))
) / 1024 / 1024 / 1024

# Multiply by $0.09 for AWS cost
```

## Summary

### Quick Health Check

```bash
# Check current bandwidth usage
curl -s http://localhost:9090/api/v1/query?query='sum(rate(rtc_socket_network_outbound_ws_bytes_total[1m]))/1024/1024' | jq -r '.data.result[0].value[1]'

# Expected: < 10 MB/sec for normal operation
```

### Critical Thresholds

| Metric | Warning | Critical |
|--------|---------|----------|
| Total bandwidth | 50 MB/sec | 100 MB/sec |
| p95 message size | 50 KB | 100 KB |
| Bandwidth per connection | 10 KB/sec | 50 KB/sec |

### Grafana Dashboard

The network metrics are visualized in the **"Network - Traffic & Bandwidth"** section:
- Inbound/outbound traffic by source/destination
- Total bandwidth gauges
- Message size distributions
- Per-node bandwidth breakdown

All panels use stacked area charts for easy visualization of traffic composition! 📊

