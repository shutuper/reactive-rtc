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

}











