package com.qqsuccubus.loadbalancer.ring;

import com.qqsuccubus.core.hash.ConsistentHashRing;
import com.qqsuccubus.core.hash.Hashers;
import com.qqsuccubus.core.metrics.MetricsNames;
import com.qqsuccubus.core.model.DistributionVersion;
import com.qqsuccubus.core.model.Heartbeat;
import com.qqsuccubus.core.model.NodeDescriptor;
import com.qqsuccubus.core.model.RingSnapshot;
import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.kafka.IRingPublisher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Manages the consistent hash ring and node registry.
 * <p>
 * Tracks node heartbeats, computes ring topology, and publishes updates.
 * Implements IRingManager for dependency inversion.
 * </p>
 */
public class RingManager implements IRingManager {
    private static final Logger log = LoggerFactory.getLogger(RingManager.class);

    private final LBConfig config;
    private final IRingPublisher kafkaPublisher;
    private final AtomicLong versionCounter = new AtomicLong(1);

    // Node registry: nodeId -> (descriptor, lastHeartbeat)
    private final Map<String, NodeEntry> nodeRegistry = new ConcurrentHashMap<>();

    // Current ring
    private volatile ConsistentHashRing currentRing;

    public RingManager(LBConfig config, IRingPublisher kafkaPublisher, MeterRegistry meterRegistry) {
        this.config = config;
        this.kafkaPublisher = kafkaPublisher;

        // Initialize empty ring
        this.currentRing = ConsistentHashRing.fromNodes(
                Collections.emptyList(),
                config.getRingVnodesPerWeight(),
                createVersion()
        );

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
     * @return Mono completing when processed
     */
    public Mono<Void> processHeartbeat(Heartbeat heartbeat) {
        String nodeId = heartbeat.getNodeId();
        NodeDescriptor descriptor = toNodeDescriptor(heartbeat);

        NodeEntry existing = nodeRegistry.get(nodeId);
        boolean isNew = (existing == null);

        // Update registry
        nodeRegistry.put(nodeId, new NodeEntry(descriptor, Instant.now(), heartbeat));

        if (isNew) {
            log.info("New node joined: {}", nodeId);
            return recomputeRing("node-joined: " + nodeId);
        }

        // Check if weight changed (significant)
        if (existing != null && existing.descriptor.getWeight() != descriptor.getWeight()) {
            log.info("Node weight changed: {} ({} -> {})",
                    nodeId, existing.descriptor.getWeight(), descriptor.getWeight());
            return recomputeRing("weight-changed: " + nodeId);
        }

        return Mono.empty();
    }

    /**
     * Resolves a userId to a node.
     *
     * @param userId User identifier
     * @return NodeDescriptor, or null if ring is empty
     */
    public NodeDescriptor resolveNode(String userId) {
        return currentRing.successor(userId.getBytes());
    }

    /**
     * Gets the current ring snapshot.
     *
     * @return RingSnapshot
     */
    public RingSnapshot getRingSnapshot() {
        return currentRing.toSnapshot();
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
     * Gets the number of physical nodes in the ring.
     *
     * @return Physical node count
     */
    public int getPhysicalNodeCount() {
        return nodeRegistry.size();
    }

    /**
     * Recomputes the ring topology and publishes update.
     *
     * @param reason Human-readable reason for recompute
     * @return Mono completing when published
     */
    private Mono<Void> recomputeRing(String reason) {
        List<NodeDescriptor> nodes = nodeRegistry.values().stream()
                .map(e -> e.descriptor)
                .collect(Collectors.toList());

        if (nodes.isEmpty()) {
            log.warn("No active nodes; ring is empty");
            return Mono.empty();
        }

        DistributionVersion version = createVersion();
        ConsistentHashRing newRing = ConsistentHashRing.fromNodes(nodes, config.getRingVnodesPerWeight(), version);
        this.currentRing = newRing;

        log.info("Ring recomputed: version={}, nodes={}, vnodes={}, reason={}",
                version.getVersion(), newRing.getPhysicalNodeCount(), newRing.getVnodeCount(), reason);

        // Publish to Kafka
        ControlMessages.RingUpdate ringUpdate = ControlMessages.RingUpdate.builder()
                .version(version)
                .ring(newRing.toSnapshot())
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
        String ringId = Hashers.toHex(Hashers.sha256(
                nodeRegistry.keySet().stream().sorted().collect(Collectors.joining(","))
        ));
        return DistributionVersion.builder()
                .version(version)
                .issuedAt(Instant.now())
                .ringId(ringId)
                .build();
    }

    private NodeDescriptor toNodeDescriptor(Heartbeat hb) {
        // Generate public WS URL using template
        String publicWsUrl = String.format(config.getPublicDomainTemplate(), 8080);

        return NodeDescriptor.builder()
                .nodeId(hb.getNodeId())
                .publicWsUrl(publicWsUrl)
                .zone("default")
                .weight(computeWeight(hb))
                .capacityConn(5000)
                .capacityMps(2500)
                .meta(Collections.emptyMap())
                .build();
    }

    private int computeWeight(Heartbeat hb) {
        // Simple weight: inversely proportional to load
        // In production, use more sophisticated logic
        double load = hb.getActiveConn() / 5000.0;
        if (load < 0.3) return 3;
        if (load < 0.7) return 2;
        return 1;
    }

    /**
     * Node entry with descriptor and last-seen timestamp.
     */
    public static class NodeEntry {
        public final NodeDescriptor descriptor;
        public final Instant lastSeen;
        public final Heartbeat lastHeartbeat;

        public NodeEntry(NodeDescriptor descriptor, Instant lastSeen, Heartbeat lastHeartbeat) {
            this.descriptor = descriptor;
            this.lastSeen = lastSeen;
            this.lastHeartbeat = lastHeartbeat;
        }
    }
}

