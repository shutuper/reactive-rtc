package com.qqsuccubus.core.hash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qqsuccubus.core.model.DistributionVersion;
import com.qqsuccubus.core.model.NodeDescriptor;
import com.qqsuccubus.core.model.RingSnapshot;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable weighted consistent hash ring with virtual nodes.
 * <p>
 * <b>Consistent hashing properties:</b>
 * <ul>
 *   <li>Adding/removing a node reassigns approximately 1/n of keys (minimal disruption).</li>
 *   <li>Virtual nodes enable weighted distribution: nodes with higher weight get more vnodes.</li>
 *   <li>Deterministic key → node mapping based on hash function.</li>
 * </ul>
 * </p>
 * <p>
 * <b>Thread-safety:</b> Immutable after construction; safe for concurrent reads.
 * </p>
 */
public class ConsistentHashRing {
    private static final Logger log = LoggerFactory.getLogger(ConsistentHashRing.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final TreeMap<Long, String> ring; // hash → nodeId
    private final Map<String, NodeDescriptor> nodes; // nodeId → descriptor
    private final int vnodesPerWeightUnit;
    @Getter
    private final DistributionVersion version;

    private ConsistentHashRing(
            TreeMap<Long, String> ring,
            Map<String, NodeDescriptor> nodes,
            int vnodesPerWeightUnit,
            DistributionVersion version
    ) {
        this.ring = ring;
        this.nodes = nodes;
        this.vnodesPerWeightUnit = vnodesPerWeightUnit;
        this.version = version;
    }

    /**
     * Constructs a consistent hash ring from a list of node descriptors.
     *
     * @param nodeDescriptors     List of nodes to include in the ring
     * @param vnodesPerWeightUnit Number of virtual nodes per weight unit
     * @param version             Distribution version for this ring
     * @return Immutable ConsistentHashRing instance
     */
    public static ConsistentHashRing fromNodes(
            List<NodeDescriptor> nodeDescriptors,
            int vnodesPerWeightUnit,
            DistributionVersion version
    ) {
        TreeMap<Long, String> ring = new TreeMap<>();
        Map<String, NodeDescriptor> nodeMap = new HashMap<>();

        for (NodeDescriptor node : nodeDescriptors) {
            nodeMap.put(node.getNodeId(), node);
            int vnodeCount = node.getWeight() * vnodesPerWeightUnit;

            for (int i = 0; i < vnodeCount; i++) {
                String vnodeKey = node.getNodeId() + "#" + i;
                long hash = Hashers.murmur3Hash(vnodeKey.getBytes(StandardCharsets.UTF_8));
                ring.put(hash, node.getNodeId());
            }
        }

        log.info("Created ring with {} vnodes from {} physical nodes (version={})",
                ring.size(), nodeDescriptors.size(), version.getVersion());

        return new ConsistentHashRing(ring, nodeMap, vnodesPerWeightUnit, version);
    }

    /**
     * Finds the successor node for a given key.
     * <p>
     * The successor is the first vnode whose hash is ≥ key hash (wrapping around if needed).
     * </p>
     *
     * @param key Key bytes (typically userId or channelId)
     * @return NodeDescriptor of the successor node, or null if ring is empty
     */
    public NodeDescriptor successor(byte[] key) {
        if (ring.isEmpty()) {
            return null;
        }

        long hash = Hashers.murmur3Hash(key);
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);

        if (entry == null) {
            // Wrap around to the first node
            entry = ring.firstEntry();
        }

        return nodes.get(entry.getValue());
    }

    /**
     * Finds the next K distinct physical nodes starting from a given node.
     * <p>
     * Useful for replica placement or failover candidates.
     * </p>
     *
     * @param nodeId Starting node ID
     * @param k      Number of distinct successors to find
     * @return List of up to K successor node IDs (may be fewer if not enough nodes)
     */
    public List<String> nextK(String nodeId, int k) {
        if (ring.isEmpty() || k <= 0) {
            return Collections.emptyList();
        }

        // Find first vnode for this physical node
        long startHash = ring.entrySet().stream()
                .filter(e -> e.getValue().equals(nodeId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(0L);

        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();

        // Walk clockwise around the ring
        for (Map.Entry<Long, String> entry : ring.tailMap(startHash, false).entrySet()) {
            String candidate = entry.getValue();
            if (!candidate.equals(nodeId) && seen.add(candidate)) {
                result.add(candidate);
                if (result.size() >= k) {
                    return result;
                }
            }
        }

        // Wrap around to the beginning
        for (Map.Entry<Long, String> entry : ring.headMap(startHash).entrySet()) {
            String candidate = entry.getValue();
            if (!candidate.equals(nodeId) && seen.add(candidate)) {
                result.add(candidate);
                if (result.size() >= k) {
                    return result;
                }
            }
        }

        return result;
    }

    /**
     * Estimates the fraction of keys that would be reassigned if a node is added/removed.
     * <p>
     * Theoretical expectation: ~1/n where n = number of physical nodes.
     * </p>
     *
     * @return Estimated reassignment fraction (0.0 - 1.0)
     */
    public double estimateReassignmentOnChange() {
        int physicalNodes = nodes.size();
        if (physicalNodes == 0) {
            return 0.0;
        }
        // Approximate: adding/removing one node affects 1/n of key space
        return 1.0 / (physicalNodes + 1);
    }

    /**
     * Serializes this ring to a RingSnapshot.
     *
     * @return RingSnapshot containing all vnodes and metadata
     */
    public RingSnapshot toSnapshot() {
        List<RingSnapshot.VNode> vnodes = ring.entrySet().stream()
                .map(e -> new RingSnapshot.VNode(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        return RingSnapshot.builder()
                .vnodes(vnodes)
                .hashSpaceSize(Long.MAX_VALUE) // Using full 64-bit space
                .vnodesPerWeightUnit(vnodesPerWeightUnit)
                .version(version)
                .build();
    }

    public int getVnodeCount() {
        return ring.size();
    }

    public int getPhysicalNodeCount() {
        return nodes.size();
    }

    public Map<String, NodeDescriptor> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }
}

