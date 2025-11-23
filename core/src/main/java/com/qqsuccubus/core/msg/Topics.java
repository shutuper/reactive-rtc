package com.qqsuccubus.core.msg;

/**
 * Kafka topic names used across the system.
 * <p>
 * <b>Topic partitioning strategy:</b>
 * <ul>
 *   <li>{@code CONTROL_*}: Single partition (global broadcast)</li>
 *   <li>{@code DELIVERY_NODE}: Key = targetNodeId (ensures all messages for a node go to same partition)</li>
 *   <li>{@code DELIVERY_BROADCAST}: Key = channelId (per-channel ordering)</li>
 *   <li>{@code METRICS_NODE}: Key = nodeId (per-node metrics stream)</li>
 * </ul>
 * </p>
 */
public final class Topics {
    private Topics() {
    }

    /**
     * Control topic for ring updates (RingUpdate messages).
     * Published by load-balancer, consumed by all socket nodes.
     */
    public static final String CONTROL_RING = "rtc.control.ring";

    /**
     * Control topic for scaling signals (ScaleSignal messages).
     * Published by load-balancer, consumed by autoscalers.
     */
    public static final String CONTROL_SCALE = "rtc.control.scale";

    /**
     * Control topic for drain signals (DrainSignal messages).
     * Published by load-balancer, consumed by socket nodes for graceful disconnections.
     */
    public static final String CONTROL_DRAIN = "rtc.control.drain";

    /**
     * Delivery topic for node-targeted messages (two-hop relay).
     * Key = targetNodeId, value = Envelope.
     * Each socket node consumes with group.id = nodeId.
     */
    public static final String DELIVERY_NODE = "rtc.delivery.node";

    /**
     * Delivery topic for broadcast messages (e.g., channels, rooms).
     * Key = channelId, value = Envelope.
     * All nodes consume to fan out to local subscribers.
     */
    public static final String DELIVERY_BROADCAST = "rtc.delivery.broadcast";

    /**
     * Metrics topic for node heartbeats and telemetry.
     * Key = nodeId, value = Heartbeat or custom metrics.
     * Consumed by monitoring systems.
     */
    public static final String METRICS_NODE = "rtc.metrics.node";
}

