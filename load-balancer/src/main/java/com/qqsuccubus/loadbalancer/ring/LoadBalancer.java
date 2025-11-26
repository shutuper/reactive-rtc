package com.qqsuccubus.loadbalancer.ring;

import com.qqsuccubus.core.hash.Hashers;
import com.qqsuccubus.core.hash.SkeletonWeightedRendezvousHash;
import com.qqsuccubus.core.metrics.MetricsNames;
import com.qqsuccubus.core.model.DistributionVersion;
import com.qqsuccubus.core.model.Heartbeat;
import com.qqsuccubus.core.model.RingSnapshot;
import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.kafka.IRingPublisher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.internal.PlatformDependent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Manages the consistent hash ring and node registry.
 * <p>
 * Tracks node heartbeats, computes ring topology, and publishes updates.
 * Implements IRingManager for dependency inversion.
 * </p>
 */
public class LoadBalancer implements ILoadBalancer {
    private static final Logger log = LoggerFactory.getLogger(LoadBalancer.class);

    private final AtomicLong versionCounter = new AtomicLong(1);
    private final IRingPublisher kafkaPublisher;
    private final LBConfig config;

    // Node registry: nodeId -> (descriptor, lastHeartbeat)
    private final Map<String, NodeEntry> nodeRegistry = PlatformDependent.newConcurrentHashMap();

    // Current ring and version
    private volatile SkeletonWeightedRendezvousHash currentHash;
    private volatile DistributionVersion currentVersion;

    public LoadBalancer(LBConfig config, IRingPublisher kafkaPublisher, MeterRegistry meterRegistry) {
        this.kafkaPublisher = kafkaPublisher;
        this.config = config;

        // Initialize empty ring
        this.currentHash = new SkeletonWeightedRendezvousHash(Collections.emptyMap());
        this.currentVersion = createVersion();

        // Metrics
        Gauge.builder(MetricsNames.LB_RING_NODES, this, r -> r.nodeRegistry.size())
            .register(meterRegistry);

        // Start periodic stale node cleanup
        startPeriodicCleanup();
    }

    /**
     * Processes a heartbeat from a socket node.
     *
     * @param heartbeat Heartbeat message
     */
    public void processHeartbeat(List<String> activeNodes) {
        Set<String> prevActiveNodes = nodeRegistry.keySet();

        Set<String> newNodes = activeNodes.stream()
            .filter(node -> !prevActiveNodes.contains(node))
            .collect(Collectors.toSet());

        Set<String> removedNodes = prevActiveNodes.stream()
            .filter(node -> !activeNodes.contains(node))
            .collect(Collectors.toSet());

        if (removedNodes.isEmpty() && newNodes.isEmpty()) {
            return; //todo proceed
        }

        String nodeId = heartbeat.getNodeId();
        NodeDescriptor descriptor = toNodeDescriptor(heartbeat);

        NodeEntry existing = nodeRegistry.get(nodeId);
        boolean isNew = (existing == null);

        // Update registry
        nodeRegistry.put(nodeId, new NodeEntry(descriptor, Instant.now(), heartbeat));

        if (isNew) {
            log.info("New node joined: {}", nodeId);
            recomputeRing("node-joined: " + nodeId);
            return;
        }

        // Check if weight changed (significant)
        if (existing != null && existing.descriptor.getWeight() != descriptor.getWeight()) {
            log.info("Node weight changed: {} ({} -> {})",
                nodeId, existing.descriptor.getWeight(), descriptor.getWeight());
            recomputeRing("weight-changed: " + nodeId);
        }

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
     * Gets the current ring snapshot.
     *
     * @return RingSnapshot
     */
    public RingSnapshot getRingSnapshot() {
        // Build snapshot from current ring state
        List<String> nodeNames = currentHash.getNodeNames();
        Map<String, Integer> weights = currentHash.getWeights();

        // Create vnodes representation for compatibility
        // SkeletonWeightedRendezvousHash doesn't use vnodes, so we create a logical representation
        List<RingSnapshot.VNode> vnodes = new ArrayList<>();
        for (String nodeName : nodeNames) {
            Integer weight = weights.get(nodeName);
            if (weight != null) {
                // Create virtual nodes based on weight
                int vnodeCount = weight * config.getRingVnodesPerWeight();
                for (int i = 0; i < vnodeCount; i++) {
                    String vnodeKey = nodeName + "#" + i;
                    long hash = Hashers.murmur3Hash(vnodeKey.getBytes(StandardCharsets.UTF_8));
                    vnodes.add(new RingSnapshot.VNode(hash, nodeName));
                }
            }
        }

        return RingSnapshot.builder()
            .vnodes(vnodes)
            .hashSpaceSize(Long.MAX_VALUE)
            .vnodesPerWeightUnit(config.getRingVnodesPerWeight())
            .version(currentVersion)
            .build();
    }

    /**
     * Gets all active nodes.
     *
     * @return Map of nodeId → NodeEntry
     */
    public Map<String, NodeEntry> getActiveNodes() {
        return Collections.unmodifiableMap(nodeRegistry);
    }

    /**
     * Recomputes the ring topology and publishes update.
     *
     * @param reason Human-readable reason for recompute
     * @return Mono completing when published
     */
    private Mono<Void> recomputeRing(String reason) {
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
            .ring(getRingSnapshot())
            .reason(reason)
            .ts(System.currentTimeMillis())
            .build();

        return kafkaPublisher.publishRingUpdate(ringUpdate);
    }

    /**
     * Starts periodic cleanup of stale nodes (nodes that haven't sent heartbeats).
     */
    private void startPeriodicCleanup() {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                cleanupStaleNodes();
            }
        }, 30_000, 30_000); // every 30 seconds
    }

    private void cleanupStaleNodes() {
        Instant now = Instant.now();
        List<String> staleNodes = nodeRegistry.entrySet().stream()
            .filter(e -> e.getValue().lastSeen.plus(config.getHeartbeatGrace()).isBefore(now))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        if (!staleNodes.isEmpty()) {
            log.warn("Removing {} stale nodes: {}", staleNodes.size(), staleNodes);
            staleNodes.forEach(nodeRegistry::remove);
            recomputeRing("stale-nodes-removed").subscribe();
        }
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
                            String publicWsUrl,
                            int weight,
                            Instant lastSeen,
                            Heartbeat lastHeartbeat) {
    }
}

