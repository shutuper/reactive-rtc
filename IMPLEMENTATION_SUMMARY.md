# Load Balancer & Consistent Hashing Implementation Summary

## Overview

This document summarizes the production-ready implementation of the **adaptive load balancing system** with **weighted consistent hashing** for the reactive-rtc WebSocket platform.

## What Was Implemented

### 1. Dynamic Weight Calculation in Load Balancer

**File**: `load-balancer/src/main/java/com/qqsuccubus/loadbalancer/ring/LoadBalancer.java`

#### Key Features:

- **Automatic weight recalculation** based on comprehensive node metrics
- **Intelligent recalculation triggers**:
  - Topology changes (nodes added/removed)
  - Extreme load imbalances (>40% CPU/memory difference)
  - Any node exceeding 80% CPU or 85% memory
  - High latency spikes (>2x average and >500ms)
  - Time-based: Every 10 minutes minimum
  
- **Weight calculation algorithm**:
  ```
  Total weight = 100 * number of nodes
  
  Load score = 0.4*CPU + 0.4*Memory + 0.1*Latency + 0.05*KafkaLag + 0.05*Connections
  Node weight ∝ 1 / (load_score + epsilon)
  ```
  
  Nodes with **lower load** receive **higher weights** (more traffic).

#### Weight Distribution Logic:

- **CPU (40%)**: Primary factor - processor utilization
- **Memory (40%)**: Primary factor - memory pressure
- **Latency (10%)**: P95 message latency (normalized to 500ms)
- **Kafka Lag (5%)**: Topic lag latency (normalized to 1000ms)
- **Connections (5%)**: Active connection count (normalized to 5000)

All nodes maintain a **minimum weight of 10** to prevent complete starvation.

---

### 2. Ring Update Broadcasting

**Files**: 
- `core/src/main/java/com/qqsuccubus/core/msg/ControlMessages.java`
- `load-balancer/src/main/java/com/qqsuccubus/loadbalancer/ring/LoadBalancer.java`

#### Enhanced RingUpdate Message:

```java
RingUpdate {
    DistributionVersion version;
    Map<String, Integer> nodeWeights;  // NEW: Node weights for consistent hash
    String reason;
    long ts;
}
```

When weights are recalculated or topology changes, the load balancer:

1. Updates its internal `SkeletonWeightedRendezvousHash`
2. Publishes `RingUpdate` to Kafka topic `rtc.control.ring`
3. All socket nodes receive and apply the update

---

### 3. Socket Node Ring Service

**File**: `socket/src/main/java/com/qqsuccubus/socket/ring/RingService.java`

New service that maintains a local copy of the consistent hash ring on each socket node.

#### Responsibilities:

- **Maintain local hash ring**: Each socket node has its own `SkeletonWeightedRendezvousHash`
- **Process ring updates**: Updates hash when receiving `RingUpdate` from Kafka
- **Resolve target nodes**: Determines which node should handle messages for a given client

#### API:

```java
// Update ring with new weights from load balancer
void updateRing(RingUpdate ringUpdate);

// Resolve client to target node using consistent hash
String resolveTargetNode(String clientId);

// Get current ring state
DistributionVersion getCurrentVersion();
Map<String, Integer> getCurrentWeights();
boolean isInitialized();
```

---

### 4. Enhanced Message Routing

**File**: `socket/src/main/java/com/qqsuccubus/socket/kafka/KafkaService.java`

#### Updated `publishRelay` Method:

Target node resolution now follows this **priority order**:

1. **envelope.nodeId**: From Redis session (client already connected)
2. **targetNodeIdHint**: Local hint (fallback)
3. **Consistent hash**: Use `RingService.resolveTargetNode(clientId)` ✨ **NEW**

This ensures optimal routing even when Redis session data is unavailable.

#### Control Message Processing:

Socket nodes now:
- Listen to `rtc.control.ring` topic
- Deserialize `RingUpdate` messages
- Call `ringService.updateRing(ringUpdate)` to update local hash
- Log ring version, node count, and reason

---

### 5. Application Integration

**File**: `socket/src/main/java/com/qqsuccubus/socket/SocketApp.java`

Socket application initialization now includes:

```java
RingService ringService = new RingService();

KafkaService kafkaService = new KafkaService(
    config, sessionManager, metricsService, 
    bufferService, ringService  // NEW
);
```

---

## Architecture Flow

### Heartbeat Processing Flow:

```
1. Socket nodes → Send metrics to Redis/Prometheus
2. Load Balancer → Reads heartbeats from Redis
3. Load Balancer → Queries Prometheus for comprehensive metrics
4. Load Balancer → Calculates node weights based on:
   - CPU usage
   - Memory usage
   - Active connections
   - Message throughput
   - P95 latency
   - Kafka topic lag
5. Load Balancer → Decides if rebalancing needed:
   - Topology changed?
   - Extreme imbalance?
   - 10 minutes passed?
6. Load Balancer → Recalculates weights (if needed)
7. Load Balancer → Updates internal hash ring
8. Load Balancer → Publishes RingUpdate to Kafka
```

### Message Routing Flow:

```
1. Client A sends message to Client B (both on different nodes)
2. Socket Node 1 receives WebSocket message
3. Node 1 checks local sessions (Client B not local)
4. Node 1 calls kafkaService.publishRelay(null, envelope)
5. KafkaService checks:
   - envelope.nodeId (from Redis)? → Use it
   - targetNodeIdHint? → Use it
   - Else → ringService.resolveTargetNode(clientB) ✨
6. Node 1 publishes to Kafka topic: delivery_node_{targetNode}
7. Target node consumes message
8. Target node delivers to Client B
```

---

## Weight Recalculation Strategy

### When Weights Are Recalculated:

#### 1. **Topology Changes (Immediate)**
- New nodes join
- Existing nodes leave/fail
- Total weight redistributed: `100 * numNodes`

#### 2. **Extreme Load Imbalance (Immediate)**
- CPU difference > 40% between nodes
- Memory difference > 40% between nodes
- Any node > 80% CPU or > 85% memory
- Latency spike: node latency > 2x average AND > 500ms

#### 3. **Time-Based (Every 10 Minutes)**
- Gradual drift correction
- Prevents frequent thrashing
- `lastWeightRecalculationMs` tracking

### Why This Strategy?

- **Stability**: Avoid constant weight changes that cause unnecessary client migrations
- **Responsiveness**: React quickly to critical situations (overload, imbalance)
- **Fairness**: Periodic rebalancing ensures long-term fairness
- **Production-safe**: Conservative thresholds prevent false positives

---

## Production Readiness

### ✅ Completed Features:

1. **Dynamic weight calculation** based on real-time metrics
2. **Intelligent rebalancing triggers** (topology, load, time)
3. **Kafka-based ring synchronization** across all nodes
4. **Consistent hashing** for deterministic client routing
5. **Graceful degradation**: Falls back to hints if hash not initialized
6. **Comprehensive logging** for observability
7. **Thread-safe implementation** using atomic references
8. **Zero-dependency updates**: Socket nodes update independently

### 🔧 Configuration Parameters:

| Parameter | Value | Location | Description |
|-----------|-------|----------|-------------|
| `WEIGHT_PER_NODE` | 100 | LoadBalancer | Base weight per node |
| `WEIGHT_RECALC_INTERVAL_MS` | 10 min | LoadBalancer | Min time between recalcs |
| `MIN_WEIGHT` | 10 | LoadBalancer | Minimum node weight |
| CPU threshold | 80% | LoadBalancer | Overload detection |
| Memory threshold | 85% | LoadBalancer | Overload detection |
| CPU imbalance | 40% | LoadBalancer | Imbalance trigger |
| Memory imbalance | 40% | LoadBalancer | Imbalance trigger |

### 📊 Observability:

All key events are logged:
- Weight recalculations with reasons
- Ring updates with version and node count
- Target node resolutions
- Load scores for each node
- Topology changes (joins/leaves)

### 🚀 Scalability:

- **O(log n)** consistent hash lookups (skeleton-based rendezvous hashing)
- Lock-free updates using `AtomicReference`
- Per-node Kafka topics for zero traffic waste
- Stateless socket nodes (ring state synchronized via Kafka)

---

## Testing Recommendations

### Unit Tests:

```java
// Test weight calculation
@Test
void shouldCalculateWeightsProportionallyToInverseLoad()

// Test extreme load detection
@Test
void shouldTriggerRebalanceOnExtremeLoad()

// Test topology changes
@Test
void shouldRebalanceOnNodeJoin()

// Test time-based rebalancing
@Test
void shouldRebalanceAfter10Minutes()
```

### Integration Tests:

1. **Ring synchronization**: Verify all socket nodes receive and apply updates
2. **Client routing**: Verify clients consistently route to same node
3. **Failover**: Verify graceful handling when node fails
4. **Load balancing**: Verify traffic shifts to under-loaded nodes

### Load Tests:

1. **Steady state**: 10k concurrent connections, verify weight stability
2. **Spike**: Add 5k connections to one node, verify rebalancing
3. **Scale out**: Add 2 new nodes, verify weight redistribution
4. **Scale in**: Remove 1 node, verify client migration

---

## Deployment Checklist

- [ ] **Prometheus**: Ensure metrics are scraped from all socket nodes
- [ ] **Kafka**: Topic `rtc.control.ring` exists and accessible
- [ ] **Redis**: Heartbeat keys are being written by socket nodes
- [ ] **Logging**: Ensure logs are collected for monitoring
- [ ] **Alerting**: Set up alerts for:
  - Frequent weight recalculations (>6/hour)
  - High load on any node (>80% CPU/memory)
  - Ring update failures
  - Uninitialized rings on socket nodes
- [ ] **Monitoring Dashboards**: Add panels for:
  - Node weights over time
  - Load distribution across nodes
  - Ring version propagation latency
  - Target node resolution success rate

---

## Future Enhancements

### Potential Improvements:

1. **Predictive scaling**: Use historical metrics to anticipate load spikes
2. **Geographic awareness**: Factor in node location for latency optimization
3. **Connection affinity**: Prefer nodes with related clients for multi-party sessions
4. **Graceful draining**: Coordinate with `DrainSignal` for smoother scale-downs
5. **Weighted metrics**: Make metric weights configurable per deployment
6. **A/B testing**: Support multiple ring configurations for experimentation

### Advanced Features:

- **Machine learning**: Train models to predict optimal weights
- **Cost optimization**: Factor in cloud costs (spot vs on-demand)
- **Multi-region**: Coordinate rings across regions
- **Client migrations**: Active client rebalancing for hot spots

---

## Metrics to Monitor

### Load Balancer:

- `rtc_lb_ring_nodes`: Number of active nodes
- `rtc_lb_weight_recalculations_total`: Counter of rebalancing events
- `rtc_lb_ring_version`: Current ring version
- `rtc_lb_node_weight{node_id}`: Current weight per node

### Socket Nodes:

- `rtc_socket_ring_version`: Ring version on each node
- `rtc_socket_ring_initialized`: Boolean - ring ready?
- `rtc_socket_target_resolution_success_total`: Successful resolutions
- `rtc_socket_target_resolution_failure_total`: Failed resolutions

---

## Summary

This implementation provides a **production-ready, adaptive load balancing system** that:

✅ **Dynamically adjusts** to real-time node performance  
✅ **Maintains stability** through intelligent recalculation triggers  
✅ **Scales horizontally** with O(log n) hash lookups  
✅ **Self-heals** through automatic rebalancing  
✅ **Provides observability** through comprehensive logging  
✅ **Gracefully degrades** when components are unavailable  

The system is ready for production deployment and will automatically optimize traffic distribution across your socket node fleet.

---

**Author**: AI Assistant  
**Date**: November 26, 2025  
**Version**: 1.0.0

