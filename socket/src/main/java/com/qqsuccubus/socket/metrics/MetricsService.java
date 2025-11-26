package com.qqsuccubus.socket.metrics;

import com.qqsuccubus.core.metrics.MetricsNames;
import com.qqsuccubus.core.metrics.MetricsTags;
import com.qqsuccubus.socket.config.SocketConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Centralized metrics service for socket node.
 */
public class MetricsService {

    // Counters
    private final Counter deliverLocal;
    private final Counter deliverRelay;
    private final Counter resumeSuccess;
    private final Counter dropsBufferFull;

    // Network traffic counters (bytes)
    private final Counter networkInboundWs;
    private final Counter networkInboundKafka;
    private final Counter networkOutboundWs;
    private final Counter networkOutboundKafka;

    // Message size distribution summaries
    private final DistributionSummary messageSizeInbound;
    private final DistributionSummary messageSizeOutbound;

    // Timers
    private final Timer latency;
    private final Timer kafkaPublishLatency;
    private final Timer kafkaDeliryTopicLatency;

    public MetricsService(MeterRegistry registry, SocketConfig config) {

        new ProcessorMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);

        deliverLocal = Counter.builder(MetricsNames.DELIVER_MPS)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .tag(MetricsTags.TYPE, "local")
            .description("Messages delivered locally (same node)")
            .register(registry);

        deliverRelay = Counter.builder(MetricsNames.DELIVER_MPS)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .tag(MetricsTags.TYPE, "relay")
            .description("Messages delivered via relay (cross-node)")
            .register(registry);

        resumeSuccess = Counter.builder(MetricsNames.RESUME_SUCCESS_TOTAL)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .register(registry);

        dropsBufferFull = Counter.builder(MetricsNames.DROPS_TOTAL)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .tag(MetricsTags.REASON, "buffer_full")
            .description("Messages dropped due to buffer full")
            .register(registry);

        // Initialize network traffic counters
        networkInboundWs = Counter.builder(MetricsNames.NETWORK_INBOUND_WS_BYTES)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .description("Total bytes received from WebSocket clients")
            .baseUnit("bytes")
            .register(registry);

        networkInboundKafka = Counter.builder(MetricsNames.NETWORK_INBOUND_KAFKA_BYTES)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .description("Total bytes received from Kafka")
            .baseUnit("bytes")
            .register(registry);

        networkOutboundWs = Counter.builder(MetricsNames.NETWORK_OUTBOUND_WS_BYTES)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .description("Total bytes sent to WebSocket clients")
            .baseUnit("bytes")
            .register(registry);

        networkOutboundKafka = Counter.builder(MetricsNames.NETWORK_OUTBOUND_KAFKA_BYTES)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .description("Total bytes sent to Kafka")
            .baseUnit("bytes")
            .register(registry);

        // Initialize message size distribution summaries
        messageSizeInbound = DistributionSummary.builder(MetricsNames.MESSAGE_SIZE_INBOUND)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .description("Inbound message size distribution")
            .baseUnit("bytes")
            .register(registry);

        messageSizeOutbound = DistributionSummary.builder(MetricsNames.MESSAGE_SIZE_OUTBOUND)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .description("Outbound message size distribution")
            .baseUnit("bytes")
            .register(registry);

        // Initialize timers with percentile histograms for p95, p99 tracking
        latency = Timer.builder(MetricsNames.LATENCY)
            .tag(MetricsTags.NODE_ID, config.getNodeId())
            .description("End-to-end message delivery latency")
            .publishPercentileHistogram()
            .serviceLevelObjectives(
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofMillis(500),
                Duration.ofMillis(1000),
                Duration.ofMillis(2000)
            )
            .register(registry);

        kafkaPublishLatency = Timer.builder(MetricsNames.KAFKA_PUBLISH_LATENCY)
            .tag(MetricsTags.TOPIC, "delivery_node")
            .description("Kafka message publish latency")
            .publishPercentileHistogram()
            .serviceLevelObjectives(
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(250),
                Duration.ofMillis(500)
            )
            .register(registry);

        kafkaDeliryTopicLatency = Timer.builder(MetricsNames.KAFKA_DELIVERY_TOPIC_LAG_LATENCY)
            .tag(MetricsTags.TOPIC, "delivery_node")
            .description("Kafka delivery topic read lag latency")
            .publishPercentileHistogram()
            .serviceLevelObjectives(
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(250),
                Duration.ofMillis(500)
            )
            .register(registry);
    }

    public void recordDeliverLocal() {
        deliverLocal.increment();
    }

    public void recordDeliverRelay() {
        deliverRelay.increment();
    }

    public void recordResumeSuccess() {
        resumeSuccess.increment();
    }

    public void recordDropBufferFull() {
        dropsBufferFull.increment();
    }

    /**
     * Records end-to-end message delivery latency.
     * This captures the time from message reception to delivery.
     * Critical for p95 latency tracking and SLO enforcement.
     *
     * @param startNanos start nanos
     */
    public void recordLatency(long startNanos) {
        latency.record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    public void recordLatencyMs(long startMillis) {
        kafkaDeliryTopicLatency.record(System.currentTimeMillis() - startMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Records Kafka publish latency.
     * Useful for diagnosing broker-related delays.
     *
     * @param startNanos start nanos
     */
    public void recordKafkaPublishLatency(long startNanos) {
        kafkaPublishLatency.record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /**
     * Records Kafka read lag latency.
     *
     * @param startMillis start millis
     */
    public void recordKafkaDeliveryTopicLatency(long startMillis) {
        kafkaDeliryTopicLatency.record(System.currentTimeMillis() - startMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Records bytes received from WebSocket client.
     *
     * @param bytes number of bytes received
     */
    public void recordNetworkInboundWs(long bytes) {
        networkInboundWs.increment(bytes);
        messageSizeInbound.record(bytes);
    }

    /**
     * Records bytes received from Kafka.
     *
     * @param bytes number of bytes received
     */
    public void recordNetworkInboundKafka(long bytes) {
        networkInboundKafka.increment(bytes);
        messageSizeInbound.record(bytes);
    }

    /**
     * Records bytes sent to WebSocket client.
     *
     * @param bytes number of bytes sent
     */
    public void recordNetworkOutboundWs(long bytes) {
        networkOutboundWs.increment(bytes);
        messageSizeOutbound.record(bytes);
    }

    /**
     * Records bytes sent to Kafka.
     *
     * @param bytes number of bytes sent
     */
    public void recordNetworkOutboundKafka(long bytes) {
        networkOutboundKafka.increment(bytes);
        messageSizeOutbound.record(bytes);
    }

    /**
     * Gets current delivery rate (messages per second).
     * Calculates from counter increments.
     *
     * @return total message delivery count (cumulative)
     */
    public double getDeliverRate() {
        // Return approximate rate based on recent activity
        // In production, use a sliding window counter
        return deliverLocal.count() + deliverRelay.count();
    }

}

