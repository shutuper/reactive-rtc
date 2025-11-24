package com.qqsuccubus.core.metrics;

/**
 * Standard tag keys for Micrometer metrics.
 * <p>
 * Consistent tagging enables aggregation and filtering in Prometheus/Grafana.
 * </p>
 */
public final class MetricsTags {
    private MetricsTags() {
    }

    /**
     * Tag key for node identifier.
     */
    public static final String NODE_ID = "node_id";

    /**
     * Tag key for operation status (success/failure).
     */
    public static final String STATUS = "status";

    /**
     * Tag key for message type (local/relay/broadcast).
     */
    public static final String TYPE = "type";

    /**
     * Tag key for failure/drop reason.
     */
    public static final String REASON = "reason";

    /**
     * Tag key for Kafka topic.
     */
    public static final String TOPIC = "topic";

    /**
     * Tag key for Redis operation type.
     */
    public static final String OPERATION = "operation";

    /**
     * Tag key for scaling action.
     */
    public static final String ACTION = "action";

    /**
     * Tag key for network traffic source (ws/kafka).
     */
    public static final String SOURCE = "source";

    /**
     * Tag key for network traffic destination (ws/kafka).
     */
    public static final String DESTINATION = "destination";
}











