package com.qqsuccubus.core.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.Map;

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
     * Deployment zone or availability zone (for topology-aware placement).
     */
    String zone;

    /**
     * Weight factor for the consistent hash ring. Higher weight = more virtual nodes.
     * Determines the proportion of key space assigned to this node.
     */
    int weight;

    /**
     * Maximum concurrent connections this node can handle.
     */
    int capacityConn;

    /**
     * Maximum messages per second this node can handle.
     */
    int capacityMps;

    /**
     * Optional metadata (e.g., version, build, custom tags).
     */
    Map<String, String> meta;
}


