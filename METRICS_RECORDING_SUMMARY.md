# Metrics Recording Implementation Summary

This document describes how throughput and latency metrics are recorded throughout the application.

## Metrics Overview

### 1. **Throughput (Messages Per Second)**

**Metric Name:** `rtc.socket.deliver.mps`  
**Type:** Counter  
**Tags:** `node_id`, `type` (local/relay)

#### Recording Locations:

##### Local Delivery
- **File:** `SessionManager.java`
- **Method:** `deliverMessage(Envelope envelope)` (line 138)
- **Trigger:** When a message is successfully delivered to a client on the same node
- **Code:**
  ```java
  metricsService.recordDeliverLocal();
  ```

##### Relay Delivery (Cross-node)
- **File:** `KafkaService.java`
- **Method:** `listenToDeliveryMessages(String currentNodeId)` (line 203)
- **Trigger:** When a message is received from Kafka and delivered to a connected client
- **Code:**
  ```java
  metricsService.recordDeliverRelay();
  ```

### 2. **Latency (End-to-End Message Delivery)**

**Metric Name:** `rtc.socket.latency`  
**Type:** Timer (with percentile histograms for p95, p99)  
**Tags:** `node_id`  
**SLO Buckets:** 10ms, 50ms, 100ms, 200ms, 500ms, 1s, 2s

#### Recording Locations:

##### Local Delivery Path
- **File:** `WebSocketHandler.java`
- **Method:** `sendMessage(Envelope envelope)` (line 208)
- **Measurement:** From message reception to local delivery completion
- **Code:**
  ```java
  long startNanos = System.nanoTime();
  // ... local delivery ...
  metricsService.recordLatency(Duration.ofNanos(System.nanoTime() - startNanos));
  ```

##### Relay Delivery Path (Sender Side)
- **File:** `WebSocketHandler.java`
- **Method:** `sendMessage(Envelope envelope)` (line 214)
- **Measurement:** From message reception to Kafka publish completion
- **Code:**
  ```java
  long startNanos = System.nanoTime();
  // ... Kafka publish ...
  metricsService.recordLatency(Duration.ofNanos(System.nanoTime() - startNanos));
  ```

##### Relay Delivery Path (Receiver Side)
- **File:** `KafkaService.java`
- **Method:** `listenToDeliveryMessages(String currentNodeId)` (line 207)
- **Measurement:** From Kafka message receipt to client delivery
- **Code:**
  ```java
  long relayStartNanos = System.nanoTime();
  // ... deliver to client ...
  metricsService.recordLatency(Duration.ofNanos(System.nanoTime() - relayStartNanos));
  ```

### 3. **Kafka Publish Latency**

**Metric Name:** `rtc.kafka.publish.latency`  
**Type:** Timer (with percentile histograms)  
**Tags:** `topic`  
**SLO Buckets:** 5ms, 10ms, 25ms, 50ms, 100ms, 250ms

#### Recording Location:
- **File:** `KafkaService.java`
- **Method:** `publishRelay(String targetNodeIdHint, Envelope envelope)` (line 299)
- **Measurement:** Kafka broker publish time only
- **Code:**
  ```java
  long kafkaStartNanos = System.nanoTime();
  // ... Kafka send operation ...
  metricsService.recordKafkaPublishLatency(Duration.ofNanos(System.nanoTime() - kafkaStartNanos));
  ```

## Prometheus Queries

### Throughput Metrics

**Total messages per second (all types):**
```promql
rate(rtc_socket_deliver_mps_total[1m])
```

**Local delivery rate:**
```promql
rate(rtc_socket_deliver_mps_total{type="local"}[1m])
```

**Relay delivery rate:**
```promql
rate(rtc_socket_deliver_mps_total{type="relay"}[1m])
```

**Per-node throughput:**
```promql
sum by (node_id) (rate(rtc_socket_deliver_mps_total[1m]))
```

### Latency Metrics

**p95 latency:**
```promql
histogram_quantile(0.95, rate(rtc_socket_latency_bucket[5m]))
```

**p99 latency:**
```promql
histogram_quantile(0.99, rate(rtc_socket_latency_bucket[5m]))
```

**Average latency:**
```promql
rate(rtc_socket_latency_sum[5m]) / rate(rtc_socket_latency_count[5m])
```

**Latency by SLO bucket:**
```promql
rate(rtc_socket_latency_bucket[5m])
```

### Kafka Publish Latency

**p95 Kafka publish latency:**
```promql
histogram_quantile(0.95, rate(rtc_kafka_publish_latency_bucket[5m]))
```

## Autoscaling Rules

### Based on Throughput

**Scale out when:**
```promql
sum(rate(rtc_socket_deliver_mps_total[1m])) > (count(up{job="socket"}) * 10000)
```
*Scale when total MPS exceeds 10k messages/sec per pod*

### Based on Latency (SLO Enforcement)

**Scale out when:**
```promql
histogram_quantile(0.95, rate(rtc_socket_latency_bucket[5m])) > 0.5
```
*Scale when p95 latency exceeds 500ms (L_SLO_MS)*

### Combined Rule

**Scale out when either condition is met:**
```promql
(sum(rate(rtc_socket_deliver_mps_total[1m])) > (count(up{job="socket"}) * 10000))
OR
(histogram_quantile(0.95, rate(rtc_socket_latency_bucket[5m])) > 0.5)
```

## Message Flow and Metric Recording

```
┌─────────────────────────────────────────────────────────────────┐
│                        Message Flow                              │
└─────────────────────────────────────────────────────────────────┘

1. CLIENT SENDS MESSAGE → WebSocketHandler.handleInboundMessage()
                          ↓
2. WebSocketHandler.sendMessage() [START LATENCY TIMER]
                          ↓
3a. LOCAL DELIVERY        │        3b. RELAY DELIVERY
    SessionManager        │            KafkaService.publishRelay()
    ↓                     │            [START KAFKA LATENCY TIMER]
    recordDeliverLocal()  │            ↓
    recordLatency()       │            Kafka Broker
    [END TIMER]           │            ↓
                          │            recordKafkaPublishLatency()
                          │            recordLatency() [END TIMER]
                          │            ↓
                          │            Target Node Kafka Consumer
                          │            [START RELAY LATENCY TIMER]
                          │            ↓
                          │            SessionManager.deliverMessage()
                          │            ↓
                          │            recordDeliverRelay()
                          │            recordLatency() [END RELAY TIMER]
                          │            ↓
                          └────────────┘
                          Client receives message
```

## Key Implementation Details

### Timer Configuration

The latency timer is configured with:
- **Percentile histograms enabled** (`.publishPercentileHistogram()`)
- **SLO buckets** for efficient p95/p99 calculation
- **Per-node tagging** for granular monitoring

### Why Multiple Latency Measurements?

1. **Local delivery latency** - Pure same-node performance
2. **Relay send latency** - Measures time to publish to Kafka
3. **Relay receive latency** - Measures Kafka consumer → client delivery
4. **Kafka-specific latency** - Isolates broker performance

This allows diagnosing where delays occur:
- High local latency → Node CPU/memory issues
- High Kafka latency → Broker problems
- High relay latency → Network or cross-node issues

## Testing Metrics

You can test that metrics are being recorded:

```bash
# Check metrics endpoint
curl http://localhost:8080/metrics | grep rtc_socket

# Expected output includes:
# rtc_socket_deliver_mps_total{node_id="...",type="local"} X
# rtc_socket_deliver_mps_total{node_id="...",type="relay"} Y
# rtc_socket_latency_bucket{node_id="...",le="0.05"} Z
# rtc_kafka_publish_latency_bucket{topic="...",le="0.01"} W
```


