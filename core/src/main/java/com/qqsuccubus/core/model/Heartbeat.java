package com.qqsuccubus.core.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Heartbeat message sent by socket nodes to report their health and metrics.
 * <p>
 * Contains real-time operational metrics used by the scaling engine
 * to make autoscaling decisions.
 * </p>
 */
@Value
@Builder(toBuilder = true)
@With
public class Heartbeat {
    /**
     * Unique identifier for the node sending this heartbeat.
     */
    String nodeId;

    /**
     * Number of active WebSocket connections on this node.
     */
    int activeConn;

    /**
     * Messages per second throughput.
     */
    double mps;

    /**
     * 95th percentile latency in milliseconds.
     */
    double p95LatencyMs;

    double queueDepth;

    /**
     * CPU utilization as a percentage (0.0 to 1.0).
     * Example: 0.75 = 75% CPU usage
     */
    double cpu;

    /**
     * Memory utilization as a percentage (0.0 to 1.0).
     * Example: 0.80 = 80% memory usage
     */
    double mem;

    /**
     * Timestamp when this heartbeat was created (milliseconds since epoch).
     */
    long timestampMs;
}

