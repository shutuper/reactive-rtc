package com.qqsuccubus.core.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Immutable descriptor for a socket node in the cluster.
 * <p>
 * Contains identity, advertised URL, capacity hints, and metadata.
 * Used by the load-balancer to compute the consistent hash ring.
 * </p>
 */
@Value
@Builder(toBuilder = true)
@With
public class NodeDescriptor {
    /**
     * Unique identifier for this node (e.g., pod name or UUID).
     */
    String nodeId;

    /**
     * Public WebSocket URL that clients should connect to (e.g., wss://socket-node1.example.com/ws).
     */
    String publicWsUrl;

    /**
     * Weight factor for the consistent hash ring. Higher weight = more virtual nodes.
     * Determines the proportion of key space assigned to this node.
     */
    int weight;

}


