package com.qqsuccubus.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.List;

/**
 * Serializable snapshot of the consistent hash ring.
 * <p>
 * Contains the complete list of virtual nodes, hash space parameters,
 * and metadata. This snapshot is published to Kafka and consumed by
 * socket nodes to update their routing tables.
 * </p>
 */
@Value
@Builder(toBuilder = true)
@With
public class RingSnapshot {
    /**
     * List of virtual nodes in the ring, sorted by hash value.
     */
    List<VNode> vnodes;

    /**
     * Total hash space size (typically 2^64 for 64-bit hashes).
     */
    long hashSpaceSize;

    /**
     * Number of virtual nodes per weight unit.
     */
    int vnodesPerWeightUnit;

    /**
     * Distribution version associated with this snapshot.
     */
    DistributionVersion version;

    /**
     * Virtual node representation: (hash, nodeId).
     */
    @Value
    public static class VNode {
        /**
         * Hash value (position on the ring).
         */
        long hash;

        /**
         * Physical node ID that owns this virtual node.
         */
        String nodeId;
    }
}

