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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.internal.PlatformDependent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class LoadBalancer implements ILoadBalancer {
    private static final Logger log = LoggerFactory.getLogger(LoadBalancer.class);

    private final AtomicLong versionCounter = new AtomicLong(1);
    private final IRingPublisher kafkaPublisher;
    private final NodeMetricsService nodeMetricsService;
    private final LBConfig config;

    // Node registry: nodeId -> (descriptor, lastHeartbeat)
    private final Map<String, NodeEntry> nodeRegistry = PlatformDependent.newConcurrentHashMap();

    // Current ring and version
    private volatile SkeletonWeightedRendezvousHash currentHash;
    private volatile DistributionVersion currentVersion;

    public LoadBalancer(LBConfig config,
                        IRingPublisher kafkaPublisher,
                        MeterRegistry meterRegistry,
                        NodeMetricsService nodeMetricsService) {
        this.kafkaPublisher = kafkaPublisher;
        this.config = config;
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
        Set<String> prevActiveNodes = nodeRegistry.keySet();

        Set<String> newNodes = activeNodes.stream()
            .filter(node -> !prevActiveNodes.contains(node))
            .collect(Collectors.toSet());

        Set<String> removedNodes = prevActiveNodes.stream()
            .filter(node -> !activeNodes.contains(node))
            .collect(Collectors.toSet());

        boolean noTopolyUpdates = removedNodes.isEmpty() && newNodes.isEmpty();

        return nodeMetricsService.getAllNodeMetrics().map(
                nodesWithMetrics -> activeNodes.stream().map(nodeId -> {
                    NodeMetrics nodeMetrics = nodesWithMetrics.getOrDefault(
                        nodeId, NodeMetrics.builder().nodeId(nodeId).build()
                    );

                    NodeEntry nodeEntry = nodeRegistry.get(nodeId);
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

                    return new NodeEntry(nodeId, nodeEntry == null ? 100 : nodeEntry.weight, heartbeat);
                }).collect(Collectors.toList())
            )
//            .doOnNext(newNodeEntries -> removedNodes.forEach(nodeRegistry::remove))
            .doOnNext(newNodeEntries -> {
                int numbOfNodes = newNodeEntries.size();
                //todo finish it
                if (noTopolyUpdates) {
                    double cpu = newNodeEntries.stream().mapToDouble(node -> node.lastHeartbeat.getCpu()).sum();
                    double mem = newNodeEntries.stream().mapToDouble(node -> node.lastHeartbeat.getMem()).sum();

                    double cpuLoad = cpu / numbOfNodes;
                    double memLoad = mem / numbOfNodes;

                    if (cpuLoad >= 0.7 || memLoad >= 0.75) {
                        //scale in
                    } else {



                    }

                    if (cpuLoad < 0.3 && memLoad > 0.3) {

                    }

                } else {

                }
            }).thenMany(Flux.empty());
    }

    /**
     * Resolves a userId to a node.
     *
     * @param userId User identifier
     * @return NodeDescriptor, or null if ring is empty
     */
    public NodeEntry resolveNode(String userId) {
        if (nodeRegistry.isEmpty()) {
            return null;
        }

        try {
            String nodeId = currentHash.selectNode(userId);
            return nodeRegistry.get(nodeId);
        } catch (IllegalStateException e) {
            log.error("Failed to select node for userId {}: {}", userId, e.getMessage());
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
        DistributionVersion version = createVersion();
        this.currentVersion = version;

        // Create new ring with SkeletonWeightedRendezvousHash
        this.currentHash = new SkeletonWeightedRendezvousHash(nodeWeights);

        log.info("Ring recomputed: version={}, nodes={}, reason={}",
            version.getVersion(), nodeWeights.size(), reason);

        // Publish to Kafka
        ControlMessages.RingUpdate ringUpdate = ControlMessages.RingUpdate.builder()
            .version(version)
            .reason(reason)
            .ts(System.currentTimeMillis())
            .build();

        return kafkaPublisher.publishRingUpdate(ringUpdate);
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

