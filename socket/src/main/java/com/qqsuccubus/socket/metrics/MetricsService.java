package com.qqsuccubus.socket.metrics;

import com.qqsuccubus.core.metrics.MetricsNames;
import com.qqsuccubus.core.metrics.MetricsTags;
import com.qqsuccubus.socket.config.SocketConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized metrics service for socket node.
 */
public class MetricsService {

    // Gauges (backed by atomics)
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger queueDepth = new AtomicInteger(0);

    // Counters
    private final Counter handshakesSuccess;
    private final Counter handshakesFailure;
    private final Counter deliverLocal;
    private final Counter deliverRelay;
    private final Counter reconnects;
    private final Counter resumeSuccess;
    private final Counter resumeFailure;
    private final Counter dropsBufferFull;
    private final Counter dropsRateLimit;

    // Timers
    private final Timer latency;
    private final Timer kafkaPublishLatency;

    public MetricsService(MeterRegistry registry, SocketConfig config) {

        new ProcessorMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);

        // Register gauges
        Gauge.builder(MetricsNames.ACTIVE_CONNECTIONS, activeConnections, AtomicInteger::get)
                .tag(MetricsTags.NODE_ID, config.getNodeId())
                .description("Current number of active WebSocket connections")
                .register(registry);

        Gauge.builder(MetricsNames.QUEUE_DEPTH, queueDepth, AtomicInteger::get)
                .tag(MetricsTags.NODE_ID, config.getNodeId())
                .description("Current outbound message queue depth")
                .register(registry);

        // Initialize counters
        handshakesSuccess = Counter.builder(MetricsNames.HANDSHAKES_TOTAL)
                .tag(MetricsTags.NODE_ID, config.getNodeId())
                .tag(MetricsTags.STATUS, "success")
                .description("Successful WebSocket handshakes")
                .register(registry);

        handshakesFailure = Counter.builder(MetricsNames.HANDSHAKES_TOTAL)
                .tag(MetricsTags.NODE_ID, config.getNodeId())
                .tag(MetricsTags.STATUS, "failure")
                .description("Failed WebSocket handshakes")
                .register(registry);

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

        reconnects = Counter.builder(MetricsNames.RECONNECTS_TOTAL)
                .tag(MetricsTags.NODE_ID, config.getNodeId())
                .register(registry);

        resumeSuccess = Counter.builder(MetricsNames.RESUME_SUCCESS_TOTAL)
                .tag(MetricsTags.NODE_ID, config.getNodeId())
                .register(registry);

        resumeFailure = Counter.builder(MetricsNames.RESUME_FAILURE_TOTAL)
                .tag(MetricsTags.NODE_ID, config.getNodeId())
                .tag(MetricsTags.REASON, "invalid_token")
                .register(registry);

        dropsBufferFull = Counter.builder(MetricsNames.DROPS_TOTAL)
                .tag(MetricsTags.NODE_ID, config.getNodeId())
                .tag(MetricsTags.REASON, "buffer_full")
                .description("Messages dropped due to buffer full")
                .register(registry);

        dropsRateLimit = Counter.builder(MetricsNames.DROPS_TOTAL)
                .tag(MetricsTags.NODE_ID, config.getNodeId())
                .tag(MetricsTags.REASON, "rate_limit")
                .description("Messages dropped due to rate limiting")
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
                        Duration.ofMillis(5),
                        Duration.ofMillis(10),
                        Duration.ofMillis(25),
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(250)
                )
                .register(registry);
    }

    public void incrementActiveConnections() {
        activeConnections.incrementAndGet();
    }

    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    public void recordHandshakeSuccess() {
        handshakesSuccess.increment();
    }

    public void recordHandshakeFailure() {
        handshakesFailure.increment();
    }

    public void recordDeliverLocal() {
        deliverLocal.increment();
    }

    public void recordDeliverRelay() {
        deliverRelay.increment();
    }

    public void recordReconnect() {
        reconnects.increment();
    }

    public void recordResumeSuccess() {
        resumeSuccess.increment();
    }

    public void recordResumeFailure() {
        resumeFailure.increment();
    }

    public void recordDropBufferFull() {
        dropsBufferFull.increment();
    }

    public void recordDropRateLimit() {
        dropsRateLimit.increment();
    }

    /**
     * Records end-to-end message delivery latency.
     * This captures the time from message reception to delivery.
     * Critical for p95 latency tracking and SLO enforcement.
     *
     * @param duration the latency duration to record
     */
    public void recordLatency(Duration duration) {
        latency.record(duration);
    }

    /**
     * Records Kafka publish latency.
     * Useful for diagnosing broker-related delays.
     *
     * @param duration the Kafka publish duration to record
     */
    public void recordKafkaPublishLatency(Duration duration) {
        kafkaPublishLatency.record(duration);
    }

    /**
     * Returns a Timer.Sample to measure latency.
     * Usage pattern:
     * <pre>
     *   Timer.Sample sample = metricsService.startLatencyTimer();
     *   // ... do work ...
     *   sample.stop(latency);
     * </pre>
     *
     * @param registry the meter registry
     * @return a started timer sample
     */
    public Timer.Sample startLatencyTimer(MeterRegistry registry) {
        return Timer.start(registry);
    }

    /**
     * Gets the latency timer for manual recording.
     *
     * @return the latency timer
     */
    public Timer getLatencyTimer() {
        return latency;
    }

    /**
     * Gets the Kafka publish latency timer for manual recording.
     *
     * @return the Kafka publish latency timer
     */
    public Timer getKafkaPublishLatencyTimer() {
        return kafkaPublishLatency;
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

    /**
     * Gets current active connection count.
     *
     * @return number of active connections
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }

    /**
     * Gets current queue depth.
     *
     * @return current queue depth
     */
    public int getQueueDepth() {
        return queueDepth.get();
    }

    /**
     * Sets the queue depth value.
     *
     * @param depth the queue depth to set
     */
    public void setQueueDepth(int depth) {
        queueDepth.set(depth);
    }

    /**
     * Increments the queue depth.
     */
    public void incrementQueueDepth() {
        queueDepth.incrementAndGet();
    }

    /**
     * Decrements the queue depth.
     */
    public void decrementQueueDepth() {
        queueDepth.decrementAndGet();
    }

}

