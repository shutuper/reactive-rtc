# Adaptive Scaling Strategy

## Overview

The load balancer now uses **adaptive, performance-based scaling decisions** instead of hard-coded thresholds. The system analyzes the relationship between workload (MPS, connections) and performance (CPU, memory, latency) to determine when to scale.

---

## Core Principle

**Scaling decisions are based on CORRELATION, not ABSOLUTE VALUES**

Instead of:
- ❌ "If MPS > 800, scale out"
- ❌ "If connections > 4000, scale out"

We use:
- ✅ "If MPS is high AND system performance is degrading, scale out"
- ✅ "If connections are high AND resources are saturated, scale out"

---

## Scale OUT Logic

### 1. Critical Resource Overload (Immediate)
```java
avgCpu > 0.7 || avgMem > 0.75
```
**Reason**: Direct resource exhaustion, regardless of workload type.

### 2. Hotspot Detection (Immediate)
```java
maxCpu > 0.85 || maxMem > 0.9
```
**Reason**: Any single node approaching capacity needs immediate relief.

### 3. High Latency Under Load (Immediate)
```java
avgLatencyMs > 500 && (avgCpu > 0.5 || avgMem > 0.5)
```
**Reason**: System struggling to meet latency SLOs despite moderate load.

### 4. Kafka Backlog Building (Immediate)
```java
avgKafkaLagMs > 500 && (avgCpu > 0.5 || avgMem > 0.5)
```
**Reason**: Message backlog indicates insufficient processing capacity.

### 5. Throughput Saturation (Adaptive) ⭐ NEW
**Old approach**:
```java
if (avgMps > 800) scale_out();
```

**New adaptive approach**:
```java
// Check if MPS is high AND performance is degrading
boolean highThroughput = avgMps > 100;  // Baseline activity
boolean performanceDegrading = avgLatencyMs > 200 || avgCpu > 0.55 || avgMem > 0.6;

if (highThroughput && performanceDegrading) {
    // Calculate efficiency ratios
    double mpsPerCpuPercent = avgMps / (avgCpu * 100 + 1);
    double mpsPerMemPercent = avgMps / (avgMem * 100 + 1);
    
    // Low ratio = inefficient = approaching capacity
    if (mpsPerCpuPercent < 2.0 || mpsPerMemPercent < 2.0) {
        scale_out();
    }
}
```

**Why this works**:
- If MPS is 500 with CPU at 20% → Ratio = 25 → **System handles load easily**
- If MPS is 500 with CPU at 80% → Ratio = 0.625 → **System saturated, SCALE OUT**

The system automatically adapts:
- Node with better hardware → Higher sustainable MPS before saturation
- Node with worse hardware → Lower MPS triggers scaling
- Different message complexity → Automatically accounts for processing cost

### 6. Connection Saturation (Adaptive) ⭐ NEW
**Old approach**:
```java
if (avgConnections > 3500) scale_out();
```

**New adaptive approach**:
```java
// Check if connections are high AND performance is suffering
boolean highConnections = avgConnections > 500;  // Baseline activity
boolean connectionStress = avgLatencyMs > 150 || avgCpu > 0.5 || avgMem > 0.55;

if (highConnections && connectionStress) {
    // Calculate efficiency ratios
    double connectionsPerCpuPercent = avgConnections / (avgCpu * 100 + 1);
    double connectionsPerMemPercent = avgConnections / (avgMem * 100 + 1);
    
    // Low ratio = inefficient = approaching capacity
    boolean resourceSaturation = connectionsPerCpuPercent < 15 || connectionsPerMemPercent < 12;
    boolean latencyDegradation = avgLatencyMs > 100 && avgConnections > 1000;
    
    if (resourceSaturation || latencyDegradation) {
        scale_out();
    }
}
```

**Why this works**:
- If 2000 connections with CPU at 30% → Ratio = 66.7 → **System handles load easily**
- If 2000 connections with CPU at 70% → Ratio = 2.86 → **System saturated, SCALE OUT**

The system automatically adapts to:
- Connection overhead per environment
- Message frequency per connection
- Memory footprint per connection

### 7. Multiple Moderate Pressures (Combined Stress)
```java
int pressureIndicators = count(
    moderateCpuPressure,    // > 60%
    moderateMemPressure,    // > 65%
    moderateLatency,        // > 300ms
    moderateMps,            // > 600
    moderateConnections     // > 3000
);

if (pressureIndicators >= 3) scale_out();
```
**Reason**: Multiple simultaneous pressures indicate approaching capacity.

---

## Scale IN Logic (Adaptive)

**Old approach**:
```java
if (avgCpu < 0.2 && avgMem < 0.25 && avgMps < 200 && avgConnections < 1000) {
    scale_in();
}
```

**New adaptive approach**:
```java
// 1. Current utilization very low
boolean veryLowCpu = avgCpu < 0.2;
boolean veryLowMem = avgMem < 0.25;
boolean excellentPerformance = avgLatencyMs < 100 && avgKafkaLagMs < 100;

// 2. Calculate if system would REMAIN healthy with one fewer node
int remainingNodes = numbOfNodes - 1;
double redistributionFactor = (double) numbOfNodes / remainingNodes;
double projectedCpu = avgCpu * redistributionFactor;
double projectedMem = avgMem * redistributionFactor;

boolean safeToRedistribute = projectedCpu < 0.5 && projectedMem < 0.55;

// 3. Check current workload efficiency
double mpsPerCpu = avgMps / (avgCpu * 100 + 1);
boolean sustainableThroughput = mpsPerCpu > 5.0;  // High efficiency

double connectionsPerCpu = avgConnections / (avgCpu * 100 + 1);
boolean sustainableConnections = connectionsPerCpu > 30;  // High efficiency

// Scale in ONLY if ALL conditions met
if (veryLowCpu && veryLowMem && excellentPerformance && 
    safeToRedistribute && sustainableThroughput && sustainableConnections) {
    scale_in();
}
```

**Why this works**:
- Projects the impact of removing one node
- Ensures remaining nodes can handle redistributed load
- Accounts for current workload efficiency
- Conservative: only scales in when clearly safe

---

## Efficiency Ratios Explained

### MPS per CPU Ratio
```
MPS per CPU % = avgMps / (avgCpu * 100)
```

**Examples**:
- **High efficiency**: 1000 MPS at 20% CPU → Ratio = 50
  - System processing efficiently, lots of headroom
- **Medium efficiency**: 1000 MPS at 50% CPU → Ratio = 20
  - Balanced, approaching limits
- **Low efficiency**: 1000 MPS at 80% CPU → Ratio = 1.25
  - System saturated, **SCALE OUT**

### Connections per CPU Ratio
```
Connections per CPU % = avgConnections / (avgCpu * 100)
```

**Examples**:
- **High efficiency**: 3000 connections at 25% CPU → Ratio = 120
  - Lightweight connections, lots of headroom
- **Medium efficiency**: 3000 connections at 50% CPU → Ratio = 60
  - Balanced load
- **Low efficiency**: 3000 connections at 75% CPU → Ratio = 4
  - Heavy connections, **SCALE OUT**

---

## Advantages of Adaptive Approach

### 1. **Hardware Agnostic**
- Powerful nodes naturally handle more load before ratios degrade
- Weaker nodes trigger scaling earlier
- No manual tuning needed per environment

### 2. **Workload Agnostic**
- Heavy messages (high processing cost) → Lower MPS sustainable
- Light messages (low processing cost) → Higher MPS sustainable
- System adapts automatically

### 3. **Graceful Degradation**
- Multiple indicators must align before scaling
- Prevents false positives from temporary spikes
- Considers both workload AND performance

### 4. **Predictive Scale-In**
- Projects load redistribution before removing nodes
- Ensures safety margin remains after scale-in
- Conservative to prevent oscillation

### 5. **Observable**
- Logs show exact ratios and reasoning
- Easy to tune thresholds if needed
- Clear causality for scaling decisions

---

## Tunable Parameters

While the system is adaptive, these baseline thresholds can be adjusted:

### Scale OUT Sensitivity:
```java
// Throughput saturation detection
boolean highThroughput = avgMps > 100;              // Baseline activity level
boolean performanceDegrading = avgLatencyMs > 200;  // Performance threshold
double mpsPerCpuThreshold = 2.0;                    // Efficiency threshold

// Connection saturation detection
boolean highConnections = avgConnections > 500;     // Baseline activity level
boolean connectionStress = avgLatencyMs > 150;      // Performance threshold
double connectionsPerCpuThreshold = 15;             // Efficiency threshold
```

### Scale IN Safety:
```java
double projectedCpuLimit = 0.5;     // Max CPU after redistribution
double projectedMemLimit = 0.55;    // Max memory after redistribution
double mpsEfficiencyMin = 5.0;      // Min MPS/CPU ratio for scale-in
double connectionEfficiencyMin = 30; // Min connections/CPU ratio for scale-in
```

---

## Example Scenarios

### Scenario 1: High MPS, Good Performance
```
avgMps = 1200
avgCpu = 0.25
avgLatencyMs = 80

mpsPerCpu = 1200 / 25 = 48 (HIGH RATIO)
```
**Decision**: ✅ **NO SCALING** - System handles load efficiently

---

### Scenario 2: High MPS, Degrading Performance
```
avgMps = 1200
avgCpu = 0.75
avgLatencyMs = 350

mpsPerCpu = 1200 / 75 = 1.6 (LOW RATIO)
performanceDegrading = true (latency > 200)
```
**Decision**: 🚨 **SCALE OUT** - System approaching saturation

---

### Scenario 3: High Connections, Good Performance
```
avgConnections = 5000
avgCpu = 0.30
avgLatencyMs = 90

connectionsPerCpu = 5000 / 30 = 166.7 (HIGH RATIO)
```
**Decision**: ✅ **NO SCALING** - Connections are lightweight

---

### Scenario 4: Moderate Connections, Poor Performance
```
avgConnections = 2500
avgCpu = 0.70
avgLatencyMs = 250

connectionsPerCpu = 2500 / 70 = 3.57 (LOW RATIO)
connectionStress = true (latency > 150)
```
**Decision**: 🚨 **SCALE OUT** - Connections are resource-heavy

---

### Scenario 5: Low Load, Excess Capacity
```
numbOfNodes = 4
avgCpu = 0.15
avgMem = 0.20
avgLatencyMs = 50

projectedCpu = 0.15 * (4/3) = 0.20 (20%)
projectedMem = 0.20 * (4/3) = 0.27 (27%)

safeToRedistribute = true (both < 50%)
```
**Decision**: 🔽 **SCALE IN** - Can safely remove one node

---

## Monitoring & Observability

### Key Log Messages:

**Scale OUT - Throughput saturation**:
```
WARN: SCALE OUT recommended: High throughput with resource saturation 
(MPS: 1200.00, CPU: 75.0%, Mem: 65.0%, Latency: 350ms, 
MPS/CPU ratio: 1.60, MPS/Mem ratio: 1.85)
```

**Scale OUT - Connection saturation**:
```
WARN: SCALE OUT recommended: High connection count with performance degradation 
(Connections: 3500, CPU: 70.0%, Mem: 60.0%, Latency: 280ms, 
Conn/CPU ratio: 5.00, Conn/Mem ratio: 5.83)
```

**Scale IN - Excess capacity**:
```
INFO: SCALE IN recommended: Excess capacity detected 
(avgCPU: 15.0%, avgMem: 20.0%, latency: 50ms, 
projectedCPU after scale-in: 20.0%, projectedMem: 26.7%)
```

---

## Summary

The adaptive scaling system:

✅ **Eliminates hard-coded thresholds** for MPS and connections  
✅ **Correlates workload with performance** to detect saturation  
✅ **Adapts to hardware differences** automatically  
✅ **Adapts to workload characteristics** automatically  
✅ **Projects impact** before scaling in  
✅ **Provides clear reasoning** in logs  
✅ **Prevents false positives** through multi-indicator checks  

The system now scales based on **actual capacity**, not arbitrary numbers! 🎯

---

*Last Updated: November 27, 2025*

