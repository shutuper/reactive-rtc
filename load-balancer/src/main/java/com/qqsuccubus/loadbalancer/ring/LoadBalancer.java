package com.qqsuccubus.loadbalancer.ring;

import com.qqsuccubus.core.hash.Hashers;
import com.qqsuccubus.core.hash.SkeletonWeightedRendezvousHash;
import com.qqsuccubus.core.metrics.MetricsNames;
import com.qqsuccubus.core.model.DistributionVersion;
import com.qqsuccubus.core.model.Heartbeat;
import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.kafka.IRingPublisher;
import com.qqsuccubus.loadbalancer.metrics.NodeMetrics;
import com.qqsuccubus.loadbalancer.metrics.NodeMetricsService;
import com.qqsuccubus.loadbalancer.redis.IRedisService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.internal.PlatformDependent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class LoadBalancer implements ILoadBalancer {
    private static final Logger log = LoggerFactory.getLogger(LoadBalancer.class);

    private final AtomicLong versionCounter = new AtomicLong(1);
    private final IRingPublisher kafkaPublisher;
    private final NodeMetricsService nodeMetricsService;
    private final IRedisService redisService;
    private final LBConfig config;

    // Node registry: nodeId -> (descriptor, lastHeartbeat)
    private final Map<String, NodeEntry> nodeRegistry = PlatformDependent.newConcurrentHashMap();

    // Current ring and version
    private volatile SkeletonWeightedRendezvousHash currentHash;
    private volatile DistributionVersion currentVersion;

    // Last time weights were recalculated (to avoid frequent changes)
    private volatile Instant lastWeightRecalculation = Instant.now();

    // Target total weight = 100 * number of nodes
    private static final int WEIGHT_PER_NODE = 100;

    // Exponential scaling tracking
    private volatile long lastScaleOutTimeMs = 0;
    private volatile long lastScaleInTimeMs = 0;
    private final AtomicInteger consecutiveScaleOutCount = new AtomicInteger(0);
    private volatile LoadSnapshot lastLoadSnapshot = null;

    // Exponential scaling configuration
    private static final long SCALE_OUT_WINDOW_MS = 3 * 60 * 1000; // 3 minutes
    private static final long SCALE_IN_WINDOW_MS = 5 * 60 * 1000; // 5 minutes
    private static final int MAX_SCALE_OUT_COUNT = 5; // Max nodes to add at once

    public LoadBalancer(LBConfig config,
                        IRingPublisher kafkaPublisher,
                        IRedisService redisService,
                        MeterRegistry meterRegistry,
                        NodeMetricsService nodeMetricsService) {
        this.kafkaPublisher = kafkaPublisher;
        this.config = config;
        this.redisService = redisService;
        this.nodeMetricsService = nodeMetricsService;

        // Initialize empty ring
        this.currentHash = new SkeletonWeightedRendezvousHash(Collections.emptyMap());
        this.currentVersion = createVersion();

        // Metrics
        Gauge.builder(MetricsNames.LB_RING_NODES, this, r -> r.nodeRegistry.size())
            .register(meterRegistry);
    }


    public Flux<Void> processHeartbeat(List<String> activeNodes) {
        if (activeNodes == null || activeNodes.isEmpty()) {
            return Flux.empty();
        }

        long timestampMs = System.currentTimeMillis();
        Set<String> prevActiveNodes = new HashSet<>(nodeRegistry.keySet());

        Set<String> newNodes = activeNodes.stream()
            .filter(node -> !prevActiveNodes.contains(node))
            .collect(Collectors.toSet());

        Set<String> removedNodes = prevActiveNodes.stream()
            .filter(node -> !activeNodes.contains(node))
            .collect(Collectors.toSet());


        boolean topologyChanged = !removedNodes.isEmpty() || !newNodes.isEmpty();

        return nodeMetricsService.getAllNodeMetrics()
            .flatMap(nodesWithMetrics -> {
                // Build updated node entries with current metrics
                List<NodeEntry> updatedEntries = activeNodes.stream().map(nodeId -> {
                    NodeMetrics nodeMetrics = nodesWithMetrics.getOrDefault(
                        nodeId, NodeMetrics.builder().nodeId(nodeId).cpu(0.0).mem(0.0).build()
                    );

                    NodeEntry existingEntry = nodeRegistry.get(nodeId);
                    int currentWeight = existingEntry != null ? existingEntry.weight : WEIGHT_PER_NODE;

                    Heartbeat heartbeat = Heartbeat.builder()
                        .nodeId(nodeId)
                        .mem(nodeMetrics.getMem())
                        .mps(nodeMetrics.getMps())
                        .cpu(nodeMetrics.getCpu())
                        .activeConn(nodeMetrics.getActiveConn())
                        .p95LatencyMs(nodeMetrics.getP95LatencyMs())
                        .kafkaTopicLagLatencyMs(nodeMetrics.getKafkaTopicLagLatencyMs())
                        .timestampMs(timestampMs)
                        .build();

                    return new NodeEntry(nodeId, currentWeight, heartbeat);
                }).collect(Collectors.toList());
                // Decide if we need to recalculate weights
                boolean shouldRecalculateWeights = shouldRecalculateWeights(
                    updatedEntries, topologyChanged
                );

                if (shouldRecalculateWeights) {
                    log.info("Weight recalculation triggered. Topology changed: {}", topologyChanged);

                    // Calculate new weights based on metrics
                    Map<String, Integer> newWeights = calculateNodeWeights(updatedEntries);

                    // Update node entries with new weights
                    updatedEntries = updatedEntries.stream().map(entry -> new NodeEntry(
                        entry.nodeId,
                        newWeights.get(entry.nodeId),
                        entry.lastHeartbeat
                    )).collect(Collectors.toList());

                    lastWeightRecalculation = Instant.now();
                }

                // Remove stale nodes
                removedNodes.forEach(nodeRegistry::remove);

                // Update registry with new entries
                updatedEntries.forEach(entry -> nodeRegistry.put(entry.nodeId, entry));

                // Check if scaling is needed (only when topology hasn't changed from last time)
                Mono<Void> scalingSignal = Mono.empty();
                if (!topologyChanged) {
                    ScalingDecision scalingDecision = shouldScale(updatedEntries, timestampMs);
                    if (scalingDecision.scaleCount != 0) {
                        scalingSignal = publishScalingSignal(scalingDecision, updatedEntries);
                    }
                }

                if (topologyChanged) {
                    String reason = buildTopologyChangeReason(newNodes, removedNodes);
                    log.info("Topology changed: {}", reason);
                    return Mono.when(recomputeHashBalancer(reason), scalingSignal);
                } else if (shouldRecalculateWeights) {
                    return Mono.when(recomputeHashBalancer("weights-rebalanced"), scalingSignal);
                }

                return scalingSignal;
            })
            .flux()
            .onErrorResume(err -> {
                log.error("Error processing heartbeat", err);
                return Flux.empty();
            });
    }

    /**
     * Determines if the system should scale based on comprehensive metrics.
     * <p>
     * Supports exponential scaling for rapid load increases:
     * - Scale OUT (1-5 nodes): Based on load severity and recent scaling history
     * - Scale IN (-1): Conservative, one node at a time
     * - No change (0): System is balanced
     * </p>
     *
     * @param updatedEntries Current node entries with metrics
     * @param nowMs          Current timestamp
     * @return ScalingDecision with scale count and reason
     */
    private ScalingDecision shouldScale(List<NodeEntry> updatedEntries, long nowMs) {
        if (updatedEntries.isEmpty()) {
            return new ScalingDecision(0, "empty-cluster");
        }

        int numbOfNodes = updatedEntries.size();

        // Create current load snapshot
        LoadSnapshot currentLoad = captureLoadSnapshot(updatedEntries);

        // Calculate average metrics across all nodes
        double totalCpu = updatedEntries.stream().mapToDouble(x -> x.lastHeartbeat.getCpu()).sum();
        double totalMem = updatedEntries.stream().mapToDouble(x -> x.lastHeartbeat.getMem()).sum();
        double totalMps = updatedEntries.stream().mapToDouble(x -> x.lastHeartbeat.getMps()).sum();
        int totalConnections = updatedEntries.stream().mapToInt(x -> x.lastHeartbeat.getActiveConn()).sum();

        double avgCpu = totalCpu / numbOfNodes;
        double avgMem = totalMem / numbOfNodes;
        double avgMps = totalMps / numbOfNodes;
        double avgConnections = (double) totalConnections / numbOfNodes;

        double avgLatencyMs = updatedEntries.stream()
            .mapToDouble(x -> x.lastHeartbeat.getP95LatencyMs())
            .average().orElse(0.0);

        double avgKafkaLagMs = updatedEntries.stream()
            .mapToDouble(x -> x.lastHeartbeat.getKafkaTopicLagLatencyMs())
            .average().orElse(0.0);

        // Find max values for hotspot detection
        double maxCpu = updatedEntries.stream().mapToDouble(x -> x.lastHeartbeat.getCpu()).max().orElse(0.0);
        double maxMem = updatedEntries.stream().mapToDouble(x -> x.lastHeartbeat.getMem()).max().orElse(0.0);

        log.info("Scale decision metrics: avgCpu={}, avgMem={}, avgMps={}, avgConn={}, avgLatency={}ms, avgKafkaLag={}ms",
            String.format("%.1f%%", avgCpu * 100), String.format("%.1f%%", avgMem * 100),
            String.format("%.2f", avgMps), String.format("%.0f", avgConnections), 
            String.format("%.2f", avgLatencyMs), String.format("%.2f", avgKafkaLagMs));

        // ========== SCALE OUT CONDITIONS ==========

        // ========== SCALE OUT CONDITIONS ==========

        // Determine scale urgency and calculate how many nodes to add
        int scaleUrgency = 0; // 0=none, 1=moderate, 2=high, 3=critical
        String urgencyReason = "";

        // 1. Critical: CPU or Memory overload
        if (avgCpu > 0.7 || avgMem > 0.75) {
            scaleUrgency = 3; // Critical
            urgencyReason = "resource-overload";
            log.warn("CRITICAL: Resource overload detected (CPU: {}, Mem: {})",
                String.format("%.1f%%", avgCpu * 100), String.format("%.1f%%", avgMem * 100));
        }

        // 2. Critical: Any single node approaching capacity
        if (maxCpu > 0.85 || maxMem > 0.9) {
            scaleUrgency = 3; // Critical
            if (urgencyReason.isEmpty()) urgencyReason = "hotspot-detected";
            log.warn("CRITICAL: Hotspot detected (maxCpu: {}, maxMem: {})",
                String.format("%.1f%%", maxCpu * 100), String.format("%.1f%%", maxMem * 100));
        }

        // 3. High latency with moderate load (system struggling)
        if (avgLatencyMs > 500 && (avgCpu > 0.5 || avgMem > 0.5)) {
            scaleUrgency = Math.max(scaleUrgency, 2); // High
            if (urgencyReason.isEmpty()) urgencyReason = "high-latency";
            log.warn("HIGH URGENCY: High latency ({}ms) with moderate load (CPU: {}, Mem: {})",
                avgLatencyMs, String.format("%.1f%%", avgCpu * 100), String.format("%.1f%%", avgMem * 100));
        }

        // 4. High Kafka lag with moderate load (message backlog building up)
        if (avgKafkaLagMs > 500 && (avgCpu > 0.5 || avgMem > 0.5)) {
            scaleUrgency = Math.max(scaleUrgency, 2); // High
            if (urgencyReason.isEmpty()) urgencyReason = "kafka-backlog";
            log.warn("HIGH URGENCY: High Kafka lag ({}ms) with moderate load", avgKafkaLagMs);
        }

        // 7. Multiple moderate pressure indicators (combined stress)
        boolean moderateCpuPressure = avgCpu > 0.6;
        boolean moderateMemPressure = avgMem > 0.65;
        boolean moderateLatency = avgLatencyMs > 300;
        boolean moderateKafkaLag = avgKafkaLagMs > 300;

        int pressureIndicators = 0;
        if (moderateCpuPressure) pressureIndicators++;
        if (moderateMemPressure) pressureIndicators++;
        if (moderateLatency) pressureIndicators++;
        if (moderateKafkaLag) pressureIndicators++;

        if (pressureIndicators >= 4) {
            scaleUrgency = Math.max(scaleUrgency, 1); // Moderate
            if (urgencyReason.isEmpty()) urgencyReason = "multiple-pressure-indicators";
            log.warn("MODERATE URGENCY: Multiple pressure indicators ({}/5) - CPU:{}, Mem:{}, Latency:{}",
                pressureIndicators, moderateCpuPressure, moderateMemPressure, moderateLatency);
        }

        // If any scale-out condition detected, calculate how many nodes to add
        if (scaleUrgency > 0) {
            int scaleCount = calculateExponentialScaleOut(scaleUrgency, currentLoad, nowMs);
            String reason = String.format("%s (urgency=%d, adding %d nodes)", urgencyReason, scaleUrgency, scaleCount);
            return new ScalingDecision(scaleCount, reason);
        }

        // ========== SCALE IN CONDITIONS ==========

        // Only consider scaling in if we have more than 2 nodes (maintain minimum)
        if (numbOfNodes > 2 && (nowMs - lastScaleInTimeMs) > SCALE_IN_WINDOW_MS) {
            // Scale in when system has excess capacity
            // Key indicators:
            // 1. Very low resource utilization (CPU, Memory)
            // 2. Good performance metrics (low latency, low Kafka lag)
            // 3. System can handle current load with one fewer node

            boolean veryLowCpu = avgCpu < 0.2;
            boolean veryLowMem = avgMem < 0.25;
            boolean lowLatency = avgLatencyMs < 250 && avgKafkaLagMs < 250;

            // Calculate if system would still be healthy with one fewer node
            // Assume load would redistribute: each remaining node gets (N/(N-1)) of current load
            int remainingNodes = numbOfNodes - 1;
            double redistributionFactor = (double) numbOfNodes / remainingNodes;
            double projectedCpu = avgCpu * redistributionFactor;
            double projectedMem = avgMem * redistributionFactor;

            // Conservative: only scale in if projected CPU/mem would still be under 50%
            boolean safeToRedistribute = projectedCpu < 0.5 && projectedMem < 0.55;

            // Scale in only if ALL conditions are met
            if (veryLowCpu && veryLowMem && lowLatency && safeToRedistribute) {
                log.info("SCALE IN recommended: Excess capacity detected " +
                         "(avgCPU: {}, avgMem: {}, latency: {}ms, projectedCPU after scale-in: {}, projectedMem: {})",
                    String.format("%.1f%%", avgCpu * 100), String.format("%.1f%%", avgMem * 100),
                    avgLatencyMs, String.format("%.1f%%", projectedCpu * 100),
                    String.format("%.1f%%", projectedMem * 100));

                // Reset scale-out tracking when scaling in
                consecutiveScaleOutCount.set(0);
                lastScaleInTimeMs = System.currentTimeMillis();
                return new ScalingDecision(-1, "excess-capacity");
            }
        }

        // No scaling needed - system is balanced
        log.info("No scaling needed - system is balanced");

        // Update last load snapshot even if not scaling
        lastLoadSnapshot = currentLoad;

        return new ScalingDecision(0, "balanced");
    }

    /**
     * Calculates exponential scale-out count based on urgency and recent history.
     *
     * @param urgency     Urgency level (1=moderate, 2=high, 3=critical)
     * @param currentLoad Current system load snapshot
     * @param nowMs       Current timestamp
     * @return Number of nodes to add (1-5)
     */
    private int calculateExponentialScaleOut(int urgency, LoadSnapshot currentLoad, long nowMs) {
        // Base scale count by urgency
        int baseScale = switch (urgency) {
            case 3 -> 3; // Critical: start with 3 nodes
            case 2 -> 2; // High: start with 2 nodes
            default -> 1; // Moderate: start with 1 node
        };

        // Check if we scaled recently (within 5 minutes)
        boolean recentlyScaled = (nowMs - lastScaleOutTimeMs) < SCALE_OUT_WINDOW_MS;

        // Calculate load increase rate if we have previous snapshot
        double loadIncreaseMultiplier = 1.0;
        if (recentlyScaled && lastLoadSnapshot != null) {
            loadIncreaseMultiplier = calculateLoadIncrease(lastLoadSnapshot, currentLoad);

            // If load increased significantly despite recent scaling, scale more aggressively
            if (loadIncreaseMultiplier > 1.5) {
                log.warn("EXPONENTIAL SCALING: Load increased {}x despite recent scale-out",
                    String.format("%.2f", loadIncreaseMultiplier));
                baseScale = Math.min(baseScale + 2, MAX_SCALE_OUT_COUNT);
            } else if (loadIncreaseMultiplier > 1.2) {
                baseScale = Math.min(baseScale + 1, MAX_SCALE_OUT_COUNT);
            }

            // Exponential backoff: if we've scaled multiple times recently, be more aggressive
            if (consecutiveScaleOutCount.get() >= 2) {
                log.warn("EXPONENTIAL SCALING: {} consecutive scale-outs in 5 minutes",
                    consecutiveScaleOutCount);
                baseScale = Math.min(baseScale + consecutiveScaleOutCount.get(), MAX_SCALE_OUT_COUNT);
            }
        }

        // Update tracking
        lastScaleOutTimeMs = nowMs;
        if (recentlyScaled) {
            consecutiveScaleOutCount.getAndIncrement();
        } else {
            consecutiveScaleOutCount.set(1);
        }
        lastLoadSnapshot = currentLoad;

        int finalScale = Math.min(baseScale, MAX_SCALE_OUT_COUNT);

        log.info("Exponential scale-out calculation: urgency={}, baseScale={}, consecutive={}, loadIncrease={}x, finalScale={}",
            urgency, baseScale, consecutiveScaleOutCount,
            String.format("%.2f", loadIncreaseMultiplier), finalScale);

        return finalScale;
    }

    /**
     * Captures current system load snapshot for trend analysis.
     */
    private LoadSnapshot captureLoadSnapshot(List<NodeEntry> entries) {
        double avgCpu = entries.stream().mapToDouble(e -> e.lastHeartbeat.getCpu()).average().orElse(0.0);
        double avgMem = entries.stream().mapToDouble(e -> e.lastHeartbeat.getMem()).average().orElse(0.0);
        double avgMps = entries.stream().mapToDouble(e -> e.lastHeartbeat.getMps()).average().orElse(0.0);
        double avgConn = entries.stream().mapToInt(e -> e.lastHeartbeat.getActiveConn()).average().orElse(0.0);
        double avgLatency = entries.stream().mapToDouble(e -> e.lastHeartbeat.getP95LatencyMs()).average().orElse(0.0);
        double kafkaReadLatency = entries.stream().mapToDouble(e -> e.lastHeartbeat.getKafkaTopicLagLatencyMs()).average().orElse(0.0);

        return new LoadSnapshot(avgCpu, avgMem, avgMps, avgConn, avgLatency, kafkaReadLatency);
    }

    /**
     * Calculates load increase ratio between two snapshots.
     * Returns multiplier > 1.0 if load increased, < 1.0 if decreased.
     */
    private double calculateLoadIncrease(LoadSnapshot previous, LoadSnapshot current) {
        // Calculate weighted increase across all metrics
        double cpuIncrease = current.cpu / (previous.cpu + 0.01);
        double memIncrease = current.mem / (previous.mem + 0.01);
        double mpsIncrease = current.mps / (previous.mps + 1.0);
        double connIncrease = current.connections / (previous.connections + 1.0);
        double latencyIncrease = current.latencyMs / (previous.latencyMs + 1.0);

        // Weighted average: CPU and memory are most important
        return (cpuIncrease * 0.3 + memIncrease * 0.3 +
                mpsIncrease * 0.2 + connIncrease * 0.1 + latencyIncrease * 0.1);
    }

    /**
     * Immutable snapshot of system load at a point in time.
     */
    private record LoadSnapshot(
        double cpu,
        double mem,
        double mps,
        double connections,
        double latencyMs,
        double kafkaReadLatencyMs
    ) {
    }

    /**
     * Scaling decision with count and reason.
     */
    private record ScalingDecision(
        int scaleCount,  // Positive = scale out, negative = scale in, 0 = no change
        String reason
    ) {
    }

    /**
     * Determines if weights should be recalculated based on:
     * 1. Topology changes (nodes added/removed)
     * 2. Extreme load imbalance
     * 3. Weight equalization need (unequal weights but balanced load)
     * 4. Weight convergence - prevents unnecessary rebalancing when system is stable
     */
    private boolean shouldRecalculateWeights(List<NodeEntry> entries, boolean topologyChanged) {
        if (topologyChanged) {
            return true;
        }

        // Check for extreme load imbalance
        if (entries.isEmpty()) {
            return false;
        }

        boolean lowLoad = entries.stream().allMatch( // each node loaded less than 40%
            node -> node.lastHeartbeat.getCpu() < 0.4 && node.lastHeartbeat.getMem() < 0.4
        );

        if (lowLoad) {
            return false;
        }

        // Check convergence status
        // If converged (weights and load both balanced, system stable), skip recalculation
        // If NOT converged, the method returns false which means we should continue checking
        ConvergenceStatus status = checkConvergenceStatus(entries);

        if (status == ConvergenceStatus.CONVERGED_AND_STABLE) {
            log.info("System has converged to balanced state - skipping unnecessary weight recalculation");
            return false;
        } else if (status == ConvergenceStatus.NEEDS_EQUALIZATION) {
            log.info("Load has balanced but weights are unequal - will equalize weights");
            return true; // Recalculate to equalize
        }

        // CRITICAL: Check if overloaded nodes have unfairly high weights (bypass cooldown)
        // This detects when a node is struggling (CPU >80% or Mem >85%) but still receiving
        // disproportionately high traffic due to its weight not being reduced yet
        if (hasOverloadedNodeWithHighWeight(entries)) {
            log.warn("Overloaded node detected with high weight - bypassing cooldown to reduce its traffic");
            return true; // URGENT: Must reduce weight immediately
        }

        // Apply cooldown for non-critical weight adjustments
        if (lastWeightRecalculation.isAfter(Instant.now().minus(2, ChronoUnit.MINUTES))) {
            log.info("Weight recalculation skipped - within 2-minute cooldown period");
            return false;
        }

        double maxCpu = entries.stream().mapToDouble(e -> e.lastHeartbeat.getCpu()).max().orElse(0.0);
        double minCpu = entries.stream().mapToDouble(e -> e.lastHeartbeat.getCpu()).min().orElse(0.0);
        double maxMem = entries.stream().mapToDouble(e -> e.lastHeartbeat.getMem()).max().orElse(0.0);
        double minMem = entries.stream().mapToDouble(e -> e.lastHeartbeat.getMem()).min().orElse(0.0);
        double maxLatency = entries.stream().mapToDouble(e -> e.lastHeartbeat.getP95LatencyMs()).max().orElse(0.0);
        double avgLatency = entries.stream().mapToDouble(e -> e.lastHeartbeat.getP95LatencyMs()).average().orElse(0.0);

        // Extreme situations that warrant immediate rebalancing:
        // 1. Large CPU imbalance (>40% difference)
        boolean extremeCpuImbalance = (maxCpu - minCpu) > 0.4;

        // 2. Large memory imbalance (>40% difference)
        boolean extremeMemImbalance = (maxMem - minMem) > 0.4;

        // 3. Any node above 80% CPU or 85% memory
        boolean anyNodeOverloaded = entries.stream()
            .anyMatch(e -> e.lastHeartbeat.getCpu() > 0.8 || e.lastHeartbeat.getMem() > 0.85);

        // 4. Large latency spike (node latency >2x average and >500ms)
        boolean extremeLatencyImbalance = maxLatency > 500 && maxLatency > avgLatency * 2;

        boolean shouldRebalance = extremeCpuImbalance || extremeMemImbalance ||
                                  anyNodeOverloaded || extremeLatencyImbalance;

        if (shouldRebalance) {
            log.info("Extreme load detected - CPU imbalance: {}, Mem imbalance: {}, Overloaded: {}, Latency spike: {}",
                extremeCpuImbalance, extremeMemImbalance, anyNodeOverloaded, extremeLatencyImbalance);
        }

        return shouldRebalance;
    }

    /**
     * Checks if any overloaded node has unfairly high weight.
     * <p>
     * Detects critical situation where a node is overloaded (CPU >80% or Mem >85%)
     * but still has weight above or near average, meaning it's receiving too much traffic.
     * This requires immediate weight reduction to prevent cascading failure.
     * </p>
     *
     * @param entries Current node entries
     * @return true if overloaded node has disproportionately high weight
     */
    private boolean hasOverloadedNodeWithHighWeight(List<NodeEntry> entries) {
        if (entries.size() < 2) {
            return false;
        }

        // Calculate average weight
        double avgWeight = entries.stream()
            .mapToInt(NodeEntry::weight)
            .average()
            .orElse(WEIGHT_PER_NODE);

        // Find nodes that are both overloaded AND have high weight
        for (NodeEntry entry : entries) {
            boolean isOverloaded = entry.lastHeartbeat.getCpu() > 0.80 ||
                                   entry.lastHeartbeat.getMem() > 0.85;

            // Node is getting unfair traffic if its weight is >= 80% of average
            // (should be getting LESS traffic, not average or more)
            boolean hasHighWeight = entry.weight >= avgWeight * 0.80;

            if (isOverloaded && hasHighWeight) {
                log.warn("Overloaded node detected with high weight: {} (CPU: {:.1f}%, Mem: {:.1f}%, weight: {} vs avg: {:.1f})",
                    entry.nodeId,
                    entry.lastHeartbeat.getCpu() * 100,
                    entry.lastHeartbeat.getMem() * 100,
                    entry.weight,
                    avgWeight);
                return true;
            }
        }

        return false;
    }

    /**
     * Convergence status enum.
     */
    private enum ConvergenceStatus {
        CONVERGED_AND_STABLE,  // No recalculation needed
        NEEDS_EQUALIZATION,     // Must recalculate to equalize weights
        NOT_CONVERGED           // Normal imbalance detection logic applies
    }

    /**
     * Checks the system convergence status.
     * <p>
     * Three possible states:
     * 1. CONVERGED_AND_STABLE: Weights match load, no recalculation needed
     * 2. NEEDS_EQUALIZATION: Load balanced but weights unequal, must equalize
     * 3. NOT_CONVERGED: System still balancing, normal checks apply
     * </p>
     *
     * @param entries Current node entries
     * @return Convergence status
     */
    private ConvergenceStatus checkConvergenceStatus(List<NodeEntry> entries) {
        if (entries.size() < 2) {
            return ConvergenceStatus.CONVERGED_AND_STABLE;
        }

        // Measure weight variance
        int maxWeight = entries.stream().mapToInt(NodeEntry::weight).max().orElse(100);
        int minWeight = entries.stream().mapToInt(NodeEntry::weight).min().orElse(100);
        double weightVariance = (double) (maxWeight - minWeight) / WEIGHT_PER_NODE;

        // Measure load variance
        double maxCpu = entries.stream().mapToDouble(e -> e.lastHeartbeat.getCpu()).max().orElse(0.0);
        double minCpu = entries.stream().mapToDouble(e -> e.lastHeartbeat.getCpu()).min().orElse(0.0);
        double maxMem = entries.stream().mapToDouble(e -> e.lastHeartbeat.getMem()).max().orElse(0.0);
        double minMem = entries.stream().mapToDouble(e -> e.lastHeartbeat.getMem()).min().orElse(0.0);

        double cpuVariance = maxCpu - minCpu;
        double memVariance = maxMem - minMem;

        // Check if all nodes are healthy
        double avgCpu = entries.stream().mapToDouble(e -> e.lastHeartbeat.getCpu()).average().orElse(0.0);
        double avgMem = entries.stream().mapToDouble(e -> e.lastHeartbeat.getMem()).average().orElse(0.0);
        boolean allNodesHealthy = avgCpu < 0.7 && avgMem < 0.7 && maxCpu < 0.80 && maxMem < 0.85;

        // Scenario 1: Weights are balanced (≈100 each) AND load is balanced
        // → System is optimal, no recalculation needed
        boolean weightsAreBalanced = weightVariance < 0.20; // Within 80-120 range
        boolean loadIsBalanced = cpuVariance < 0.20 && memVariance < 0.20; // Very balanced (<15% diff)

        if (weightsAreBalanced && loadIsBalanced && allNodesHealthy) {
            log.info("System converged: weights and load both balanced (optimal state)");
            return ConvergenceStatus.CONVERGED_AND_STABLE;
        }

        // Scenario 2: Weights are unbalanced BUT load is balanced
        // → This means unequal weights are NOT needed anymore, should equalize
        // Example: weights={130, 70, 130}, load={40%, 40%, 40%} → Should recalculate to {100, 100, 100}
        boolean weightsAreUnbalanced = weightVariance > 0.15;
        boolean loadHasBecomeBalanced = cpuVariance < 0.15 && memVariance < 0.15;

        if (weightsAreUnbalanced && loadHasBecomeBalanced && allNodesHealthy) {
            log.info("Load has balanced despite unequal weights - will equalize weights " +
                     "(weightVariance={}%, loadVariance={}%)",
                weightVariance * 100, Math.max(cpuVariance, memVariance) * 100);
            return ConvergenceStatus.NEEDS_EQUALIZATION;
        }

        // Scenario 3: Weights are working (unbalanced weights balancing unbalanced load)
        // → System is doing its job, don't interfere
        // Example: weights={130, 70, 130}, load={35%, 55%, 35%} → Keep current weights
        boolean weightsAreWorkingToBalance = weightsAreUnbalanced && !loadIsBalanced;
        boolean loadNotExtreme = cpuVariance < 0.35 && memVariance < 0.35; // < 35% difference

        if (weightsAreWorkingToBalance && loadNotExtreme && allNodesHealthy) {
            log.info("Weights are actively balancing load - maintaining current distribution");
            return ConvergenceStatus.CONVERGED_AND_STABLE;
        }

        // Default: not converged, allow normal imbalance detection logic to decide
        return ConvergenceStatus.NOT_CONVERGED;
    }

    /**
     * Calculates optimal weights for nodes based on their metrics.
     * Lower load/latency = higher weight (more traffic)
     * Total weight = 100 * numNodes
     */
    private Map<String, Integer> calculateNodeWeights(List<NodeEntry> entries) {
        if (entries.isEmpty()) {
            return Collections.emptyMap();
        }

        int numNodes = entries.size();
        int targetTotalWeight = WEIGHT_PER_NODE * numNodes;
        
        // Check if we have valid metrics - if all nodes have 0 CPU/Memory, metrics aren't available yet
        boolean hasValidMetrics = entries.stream()
            .anyMatch(e -> e.lastHeartbeat.getCpu() > 0.0 || e.lastHeartbeat.getMem() > 0.0 
                || e.lastHeartbeat.getActiveConn() > 0 || e.lastHeartbeat.getP95LatencyMs() > 0.0);
        
        // If no valid metrics, assign equal weights to all nodes
        if (!hasValidMetrics) {
            log.info("No valid metrics available yet - assigning equal weights to all nodes");
            Map<String, Integer> equalWeights = new HashMap<>();
            int weightPerNode = targetTotalWeight / numNodes;
            for (NodeEntry entry : entries) {
                equalWeights.put(entry.nodeId, weightPerNode);
            }
            // Adjust last node to ensure exact total
            String lastNode = entries.get(entries.size() - 1).nodeId;
            int remainder = targetTotalWeight - (weightPerNode * (numNodes - 1));
            equalWeights.put(lastNode, remainder);
            
            log.info("Calculated node weights (equal distribution): {}", equalWeights);
            return equalWeights;
        }
        
        double maxp95 = entries.stream().mapToDouble(x -> x.lastHeartbeat.getP95LatencyMs()).max().orElse(1d);
        double maxKafkaLag = entries.stream().mapToDouble(x -> x.lastHeartbeat.getKafkaTopicLagLatencyMs()).max().orElse(1d);
        double maxActiveConn = entries.stream().mapToDouble(x -> x.lastHeartbeat.getActiveConn()).max().orElse(1d);
        
        // Avoid division by zero
        if (maxp95 <= 0.0) maxp95 = 1.0;
        if (maxKafkaLag <= 0.0) maxKafkaLag = 1.0;
        if (maxActiveConn <= 0.0) maxActiveConn = 1.0;

        // Calculate load scores for each node (lower is better)
        Map<String, Double> loadScores = new HashMap<>();
        for (NodeEntry entry : entries) {
            Heartbeat hb = entry.lastHeartbeat;

            // Composite load score (0.0 = best, higher = worse)
            // CPU and memory are primary factors (35% each)
            // Latency, kafka lag, and connection count are secondary (30% total)
            double cpuScore = hb.getCpu() * 0.35;
            double memScore = hb.getMem() * 0.35;

            // Normalize latency
            double latencyScore = Math.min(hb.getP95LatencyMs() / maxp95, 1.0) * 0.15;

            // Normalize kafka lag
            double kafkaLagScore = Math.min(hb.getKafkaTopicLagLatencyMs() / maxKafkaLag, 1.0) * 0.1;

            // Normalize active connections
            double connScore = Math.min(hb.getActiveConn() / maxActiveConn, 1.0) * 0.05;

            double totalScore = cpuScore + memScore + latencyScore + kafkaLagScore + connScore;
            
            // Defensive check (should not happen now)
            if (Double.isNaN(totalScore) || Double.isInfinite(totalScore)) {
                log.warn("Unexpected invalid load score for {}: cpu={}, mem={}, latency={}, kafkaLag={}, conn={}, total={}. Using default.", 
                    entry.nodeId, cpuScore, memScore, latencyScore, kafkaLagScore, connScore, totalScore);
                totalScore = 0.5;
            }
            
            loadScores.put(entry.nodeId, totalScore);
        }

        // Calculate inverse scores (higher load = lower inverse score)
        // Add small epsilon to avoid division by zero
        double epsilon = 0.1;
        Map<String, Double> inverseScores = new HashMap<>();
        for (Map.Entry<String, Double> entry : loadScores.entrySet()) {
            // Invert the score: nodes with low load get high inverse scores
            inverseScores.put(entry.getKey(), 1.0 / (entry.getValue() + epsilon));
        }

        // Calculate total inverse score
        double totalInverseScore = inverseScores.values().stream().mapToDouble(Double::doubleValue).sum();

        // Distribute weights proportionally to inverse scores
        Map<String, Integer> weights = new HashMap<>();
        int assignedWeight = 0;

        List<String> nodeIds = new ArrayList<>(inverseScores.keySet());
        for (int i = 0; i < nodeIds.size(); i++) {
            String nodeId = nodeIds.get(i);
            double inverseScore = inverseScores.get(nodeId);

            int weight;
            if (i == nodeIds.size() - 1) {
                // Last node gets remaining weight to ensure sum is exact
                weight = targetTotalWeight - assignedWeight;
            } else {
                weight = (int) Math.round((inverseScore / totalInverseScore) * targetTotalWeight);
                // Ensure minimum weight of 10 (1% of base weight)
                weight = Math.max(10, weight);
            }

            weights.put(nodeId, weight);
            assignedWeight += weight;
        }

        log.info("Calculated node weights: {}", weights);
        log.info("Load scores: {}", loadScores);

        return weights;
    }

    /**
     * Builds a human-readable reason for topology changes.
     */
    private String buildTopologyChangeReason(Set<String> newNodes, Set<String> removedNodes) {
        StringBuilder reason = new StringBuilder();

        if (!newNodes.isEmpty()) {
            reason.append("nodes-joined: ").append(String.join(", ", newNodes));
        }

        if (!removedNodes.isEmpty()) {
            if (!reason.isEmpty()) {
                reason.append("; ");
            }
            reason.append("nodes-removed: ").append(String.join(", ", removedNodes));
        }

        return reason.toString();
    }

    /**
     * Publishes a scaling signal to Kafka for external autoscalers.
     * <p>
     * This signal can be consumed by:
     * - Kubernetes HPA (Horizontal Pod Autoscaler)
     * - KEDA (Kubernetes Event Driven Autoscaler)
     * - Custom autoscaling controllers
     * </p>
     *
     * @param scalingDecision Scaling decision with count and reason
     * @param entries         Current node entries for metrics
     * @return Mono completing when signal is published
     */
    private Mono<Void> publishScalingSignal(ScalingDecision scalingDecision, List<NodeEntry> entries) {
        int scaleCount = scalingDecision.scaleCount;
        String action = scaleCount > 0 ? "SCALE_OUT" : "SCALE_IN";
        int currentNodes = entries.size();
        int recommendedNodes = scaleCount > 0
            ? currentNodes + scaleCount
            : Math.max(2, currentNodes + scaleCount); // scaleCount is negative for scale-in

        // Calculate summary metrics for the reason
        double avgCpu = entries.stream().mapToDouble(e -> e.lastHeartbeat.getCpu()).average().orElse(0.0);
        double avgMem = entries.stream().mapToDouble(e -> e.lastHeartbeat.getMem()).average().orElse(0.0);
        double avgMps = entries.stream().mapToDouble(e -> e.lastHeartbeat.getMps()).average().orElse(0.0);
        double avgConnections = entries.stream().mapToInt(e -> e.lastHeartbeat.getActiveConn()).average().orElse(0.0);
        double avgLatency = entries.stream().mapToDouble(e -> e.lastHeartbeat.getP95LatencyMs()).average().orElse(0.0);

        String reason = String.format("%s recommended: nodes=%d->%d (+%d), cpu=%.1f%%, mem=%.1f%%, mps=%.2f, conn=%.0f, latency=%.0fms, reason=%s",
            action, currentNodes, recommendedNodes, Math.abs(scaleCount),
            avgCpu * 100, avgMem * 100, avgMps, avgConnections, avgLatency,
            scalingDecision.reason);

        log.info("Publishing scaling signal: {}", reason);

        ControlMessages.ScaleSignal scaleSignal = ControlMessages.ScaleSignal.builder()
            .reason(reason)
            .ts(System.currentTimeMillis())
            .build();

        return kafkaPublisher.publishScaleSignal(scaleSignal)
            .doOnError(err -> log.error("Failed to publish scaling signal: {}", err.getMessage()));
    }

    /**
     * Resolves a userId to a node.
     *
     * @param clientId User identifier
     * @return NodeDescriptor, or null if ring is empty
     */
    public NodeEntry resolveNode(String clientId) {
        if (nodeRegistry.isEmpty()) {
            return null;
        }

        try {
            String nodeId = currentHash.selectNode(clientId);
            return nodeRegistry.get(nodeId);
        } catch (IllegalStateException e) {
            log.error("Failed to select node for userId {}: {}", clientId, e.getMessage());
            return null;
        }
    }

    /**
     * Gets all active nodes.
     *
     * @return Map of nodeId → NodeEntry
     */
    public Map<String, NodeEntry> getActiveNodes() {
        return Collections.unmodifiableMap(nodeRegistry);
    }

    private Mono<Void> recomputeHashBalancer(String reason) {
        // Build node weights map for SkeletonWeightedRendezvousHash
        Map<String, Integer> nodeWeights = nodeRegistry.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().weight()
            ));

        if (nodeWeights.isEmpty()) {
            log.warn("No active nodes; ring is empty");
            return Mono.empty();
        }

        // Create new version
        this.currentVersion = createVersion();

        // Create new ring with SkeletonWeightedRendezvousHash
        this.currentHash = new SkeletonWeightedRendezvousHash(nodeWeights);

        log.info("Ring recomputed: version={}, nodes={}, weights={}, reason={}",
            this.currentVersion.getVersion(), nodeWeights.size(), nodeWeights, reason);

        // Publish to Kafka with node weights so socket nodes can update their hash
        ControlMessages.RingUpdate ringUpdate = ControlMessages.RingUpdate.builder()
            .ts(System.currentTimeMillis())
            .version(this.currentVersion)
            .nodeWeights(nodeWeights)
            .reason(reason)
            .build();

        return kafkaPublisher.publishRingUpdate(ringUpdate)
            .then(redisService.setCurrentRingVersion(ringUpdate));
    }

    private DistributionVersion createVersion() {
        long version = versionCounter.getAndIncrement();
        String versionId = Hashers.toHex(Hashers.sha256(
            nodeRegistry.keySet().stream().sorted().collect(Collectors.joining(","))
        ));

        return DistributionVersion.builder()
            .issuedAt(Instant.now())
            .versionHash(versionId)
            .version(version)
            .build();
    }

    /**
     * Node entry with descriptor and last-seen timestamp.
     */
    public record NodeEntry(String nodeId,
                            int weight,
                            Heartbeat lastHeartbeat) {
    }
}

