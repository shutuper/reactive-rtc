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
     * Gauge: Current number of active WebSocket connections.
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String ACTIVE_CONNECTIONS = "rtc.socket.active_connections";

    /**
     * Counter: Total WebSocket handshakes (successful + failed).
     * <p>
     * Tags: nodeId, status (success/failure)
     * </p>
     */
    public static final String HANDSHAKES_TOTAL = "rtc.socket.handshakes.total";

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
     * Gauge: Current outbound queue depth (buffered messages).
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String QUEUE_DEPTH = "rtc.socket.queue_depth";

    /**
     * Counter: Total reconnect attempts.
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String RECONNECTS_TOTAL = "rtc.socket.reconnects.total";

    /**
     * Counter: Successful resume operations.
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String RESUME_SUCCESS_TOTAL = "rtc.socket.resume.success.total";

    /**
     * Counter: Failed resume operations (expired token, invalid signature, etc.).
     * <p>
     * Tags: nodeId, reason
     * </p>
     */
    public static final String RESUME_FAILURE_TOTAL = "rtc.socket.resume.failure.total";

    /**
     * Counter: Ring updates received.
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String RING_UPDATES_TOTAL = "rtc.socket.ring_updates.total";

    /**
     * Counter: Messages dropped due to backpressure.
     * <p>
     * Tags: nodeId, reason (buffer_full/rate_limit)
     * </p>
     */
    public static final String DROPS_TOTAL = "rtc.socket.drops.total";

    /**
     * Gauge: Current ring version on this node.
     * <p>
     * Tags: nodeId
     * </p>
     */
    public static final String RING_VERSION = "rtc.socket.ring_version";

    /**
     * Timer: Kafka message publish latency.
     * <p>
     * Tags: topic
     * </p>
     */
    public static final String KAFKA_PUBLISH_LATENCY = "rtc.kafka.publish.latency";

    /**
     * Timer: Redis operation latency.
     * <p>
     * Tags: operation (hset/hget/xadd/xread)
     * </p>
     */
    public static final String REDIS_LATENCY = "rtc.redis.latency";

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











