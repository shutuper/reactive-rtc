package com.qqsuccubus.core.metrics;

/**
 * Micrometer metric names used across the system.
 * <p>
 * <b>Naming convention:</b> {@code rtc.<component>.<metric>}
 * <ul>
 *   <li>Counters: {@code .total} suffix</li>
 *   <li>Gauges: current value (no suffix)</li>
 *   <li>Timers: {@code .latency} suffix</li>
 * </ul>
 * </p>
 */
public final class MetricsNames {
    private MetricsNames() {
    }

    /**
     * Counter: Messages delivered per second.
     * <p>
     * Tags: nodeId, type (local/relay)
     * </p>
     */
    public static final String DELIVER_MPS = "rtc.socket.deliver.mps";

    /**
     * Timer: End-to-end message delivery latency (p50, p95, p99).
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String LATENCY = "rtc.socket.latency";

    /**
     * Counter: Successful resume operations.
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String RESUME_SUCCESS_TOTAL = "rtc.socket.resume.success.total";

    /**
     * Counter: Messages dropped due to backpressure.
     * <p>
     * Tags: nodeId, reason (buffer_full/rate_limit)
     * </p>
     */
    public static final String DROPS_TOTAL = "rtc.socket.drops.total";

    /**
     * Timer: Kafka message publish latency.
     * <p>
     * Tags: topic
     * </p>
     */
    public static final String KAFKA_PUBLISH_LATENCY = "rtc.kafka.publish.latency";

    public static final String KAFKA_DELIVERY_TOPIC_LAG_LATENCY = "rtc.kafka.delivery.topic.lag.latency";


    /**
     * Gauge: Number of physical nodes in the ring.
     * <p>
     * Tags: (none)
     * </p>
     */
    public static final String LB_RING_NODES = "rtc.lb.ring.nodes";


    /**
     * Counter: Scaling decisions made.
     * <p>
     * Tags: action (scale_out/scale_in/none)
     * </p>
     */
    public static final String LB_SCALING_DECISIONS_TOTAL = "rtc.lb.scaling.decisions.total";

    /**
     * Counter: Network traffic inbound from WebSocket (bytes).
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String NETWORK_INBOUND_WS_BYTES = "rtc.socket.network.inbound.ws.bytes";

    /**
     * Counter: Network traffic inbound from Kafka (bytes).
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String NETWORK_INBOUND_KAFKA_BYTES = "rtc.socket.network.inbound.kafka.bytes";

    /**
     * Counter: Network traffic outbound to WebSocket (bytes).
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String NETWORK_OUTBOUND_WS_BYTES = "rtc.socket.network.outbound.ws.bytes";

    /**
     * Counter: Network traffic outbound to Kafka (bytes).
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String NETWORK_OUTBOUND_KAFKA_BYTES = "rtc.socket.network.outbound.kafka.bytes";

    /**
     * Distribution Summary: Inbound message size distribution (bytes).
     * <p>
     * Tags: nodeId, source (ws/kafka)
     * </p>
     */
    public static final String MESSAGE_SIZE_INBOUND = "rtc.socket.message.size.inbound";

    /**
     * Distribution Summary: Outbound message size distribution (bytes).
     * <p>
     * Tags: nodeId, destination (ws/kafka)
     * </p>
     */
    public static final String MESSAGE_SIZE_OUTBOUND = "rtc.socket.message.size.outbound";
}











