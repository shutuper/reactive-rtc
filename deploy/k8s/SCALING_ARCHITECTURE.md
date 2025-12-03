# Implementation Updates - Scaling Architecture

## Changes Made

### 1. Scaling Architecture Redesign

**BEFORE**: KEDA independently monitored multiple metrics and made scaling decisions

**AFTER**: Load-balancer makes intelligent scaling decisions and directly scales the socket StatefulSet

### Why This Change?

The load-balancer's `publishScalingSignal()` method already implements sophisticated scaling logic that considers:
- CPU utilization
- Memory utilization  
- Active connections
- Message throughput (MPS)
- P95 latency
- Kafka consumer lag
- Load trends and history
- Exponential scaling for traffic spikes
- Conservative scale-in with connection draining

Having KEDA independently monitor these same metrics would result in:
- ❌ Duplicate scaling logic
- ❌ Potential conflicts between load-balancer and KEDA decisions
- ❌ Loss of load-balancer's intelligent context

### New Scaling Flow

```
┌─────────────────────────────────────────────────────────────┐
│  1. Socket nodes send heartbeats to Redis                   │
└──────────────────┬──────────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────────────┐
│  2. Master load-balancer consumes heartbeats                │
│     (Only master via leader election)                        │
└──────────────────┬──────────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────────────┐
│  3. Load-balancer analyzes comprehensive metrics:            │
│     - CPU, Memory, Connections, MPS, Latency, Kafka Lag     │
│     - Load trends and history                               │
│     - Calculates urgency level (moderate/high/critical)     │
└──────────────────┬──────────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────────────┐
│  4. Load-balancer makes scaling decision:                    │
│     - Scale out: 1-5 nodes (exponential based on urgency)   │
│     - Scale in: -1 node (conservative)                      │
│     - No change: System balanced                            │
└──────────────────┬──────────────────────────────────────────┘
                   │
    ┌──────────────┴──────────────┐
    │                             │
┌───▼────────────────┐  ┌─────────▼────────────────────┐
│  5a. Update pod    │  │  5b. Scale StatefulSet       │
│      deletion      │  │      directly via            │
│      costs (if     │  │      Kubernetes API          │
│      scale-in)     │  │      (Fabric8 client)        │
└────────────────────┘  └──────────┬───────────────────┘
                                   │
                        ┌──────────▼───────────────────┐
                        │  6. Kubernetes scales        │
                        │     socket StatefulSet       │
                        │     (adds or removes pods)   │
                        └──────────┬───────────────────┘
                                   │
                        ┌──────────▼───────────────────┐
                        │  7. If scale-in:             │
                        │     - PreStop hook calls     │
                        │       /drain endpoint        │
                        │     - 5-minute draining      │
                        │     - Pod termination        │
                        └──────────────────────────────┘
```

### New Components

#### StatefulSetScalerService

**File**: `load-balancer/src/main/java/com/qqsuccubus/loadbalancer/k8s/StatefulSetScalerService.java`

**Purpose**: Directly scales the socket Deployment based on load-balancer decisions

**Key Methods**:
- `scaleStatefulSet(int scaleCount, String reason)` - Scales Deployment up or down
- `getCurrentReplicas()` - Gets current replica count
- Enforces min/max replica bounds (2-50)
- Auto-detects and works with both Deployment and StatefulSet

**Configuration** (in ConfigMap):
```yaml
SOCKET_WORKLOAD_NAME: "socket"
SOCKET_MIN_REPLICAS: "2"
SOCKET_MAX_REPLICAS: "50"
```

### KEDA Role Change

**BEFORE**: Primary autoscaler with multiple triggers

**AFTER**: Backup safety net with conservative thresholds

**File**: `deploy/k8s/05-keda-scaler.yaml`

KEDA now only intervenes in extreme scenarios:
- CPU > 90% (vs 70% before)
- Memory > 90% (vs 75% before)
- Longer cooldown periods (10 minutes vs 5 minutes)
- More conservative scaling (1 pod per 3 minutes vs 1 per minute)

This prevents conflicts while maintaining a safety net if the load-balancer fails.

### Added Monitoring

#### Kafka Exporter

**File**: `deploy/k8s/08-monitoring.yaml`

- Monitors Kafka broker metrics
- Consumer lag tracking
- Topic metrics
- Exposed on port 9308
- Automatically discovered by Prometheus

#### Grafana

**File**: `deploy/k8s/08-monitoring.yaml`

- Pre-configured with Prometheus datasource
- Sample dashboard included:
  - Active connections per socket node
  - P95 latency per socket node
  - CPU/Memory usage
  - Ring node count
  - Leader status
- Accessible via LoadBalancer or port-forward
- Default credentials: admin/admin

### Configuration Updates

**Added environment variables** (in `02-config.yaml`):
```yaml
SOCKET_WORKLOAD_NAME: "socket"
SOCKET_MIN_REPLICAS: "2"
SOCKET_MAX_REPLICAS: "50"
```

**Added to load-balancer deployment** (in `03-load-balancer.yaml`):
- SOCKET_WORKLOAD_NAME
- SOCKET_MIN_REPLICAS
- SOCKET_MAX_REPLICAS

### Benefits of New Architecture

1. **Single Source of Truth**: Load-balancer is the only decision-maker for scaling
2. **Intelligent Decisions**: Considers all metrics in context, not independently
3. **Faster Response**: Direct Kubernetes API calls, no intermediate systems
4. **Exponential Scaling**: Can add up to 5 nodes at once during critical load
5. **Safe Scale-In**: Updates pod deletion costs before removing nodes
6. **Consistent Logic**: Same decision logic for both scale-out and scale-in
7. **Better Observability**: All decisions logged with reasoning

### Migration Path

For existing deployments:

1. **Apply new code** with StatefulSetScalerService
2. **Update ConfigMap** with new environment variables
3. **Restart load-balancer** pods to pick up new config
4. **Update KEDA ScaledObject** to backup mode (05-keda-scaler.yaml)
5. **Deploy monitoring** (08-monitoring.yaml) for Grafana and Kafka Exporter

### Monitoring Scaling Decisions

**View scaling logs**:
```bash
kubectl logs -n rtc -l app=load-balancer -f | grep -i "scaling\|scale"
```

**Sample log output**:
```
INFO  [LoadBalancer] CRITICAL: Resource overload detected (CPU: 75.0%, Mem: 80.0%)
INFO  [LoadBalancer] Exponential scale-out calculation: urgency=3, baseScale=3, consecutive=1, loadIncrease=1.2x, finalScale=3
INFO  [LoadBalancer] Publishing scaling signal: SCALE_OUT recommended: nodes=5->8 (+3)
INFO  [StatefulSetScalerService] Scaling Deployment socket from 5 to 8 replicas. Reason: resource-overload (urgency=3, adding 3 nodes)
INFO  [StatefulSetScalerService] ✓ Successfully scaled Deployment socket to 8 replicas
```

**Prometheus queries**:
```promql
# Scaling events
rate(rtc_lb_scale_signals_total[5m])

# Current replicas
kube_deployment_spec_replicas{deployment="socket"}

# Actual replicas
kube_deployment_status_replicas{deployment="socket"}
```

**Grafana dashboard**: Pre-loaded with "Reactive RTC Overview" showing all key metrics

### Testing

**Simulate load spike**:
```bash
# Increase connections to trigger scale-out
# (use your load testing tool)

# Watch scaling happen
kubectl get deployment socket -n rtc --watch

# View logs
kubectl logs -n rtc -l app=load-balancer --tail=50 -f
```

**Verify scale-in**:
```bash
# Stop load

# Wait 5 minutes (SCALE_IN_WINDOW_MS)

# Load-balancer will detect low load and scale in
# Watch pod deletion costs being updated first
kubectl get pods -n rtc -l app=socket -o json | jq '.items[] | {name: .metadata.name, deletionCost: .metadata.annotations["controller.kubernetes.io/pod-deletion-cost"]}'
```

---

## Summary

The new architecture provides:
- ✅ Centralized, intelligent scaling decisions
- ✅ Direct control over socket Deployment
- ✅ Exponential scaling for traffic spikes
- ✅ Safe scale-in with draining and pod deletion cost optimization
- ✅ KEDA as backup safety net
- ✅ Comprehensive monitoring with Grafana and Kafka Exporter
- ✅ Better observability and debugging

All changes are backward compatible. The load-balancer now has full control over scaling decisions, leveraging its deep understanding of system load and behavior patterns.

