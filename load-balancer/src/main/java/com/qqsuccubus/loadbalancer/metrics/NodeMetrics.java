package com.qqsuccubus.loadbalancer.metrics;

import lombok.Builder;
import lombok.Data;

/**
 * Comprehensive metrics for a single socket node.
 * Contains all key performance indicators used for monitoring and autoscaling.
 */
@Data
@Builder
public class NodeMetrics {
    /**
     * Unique identifier for the node.
     */
    private String nodeId;

    /**
     * Number of active WebSocket connections on this node.
     */
    private int activeConn;

    /**
     * Messages per second throughput (local + relay).
     */
    private double mps;

    /**
     * 95th percentile latency in milliseconds.
     */
    private double p95LatencyMs;

    /**
     * Kafka delivery topic lag latency in milliseconds.
     * Measures time from message publish to consumption.
     */
    private double kafkaTopicLagLatencyMs;

    /**
     * CPU utilization as a percentage (0.0 to 1.0).
     * Example: 0.75 = 75% CPU usage
     */
    private double cpu;

    /**
     * Memory utilization as a percentage (0.0 to 1.0).
     * Example: 0.80 = 80% memory usage
     */
    private double mem;

    /**
     * Kafka consumer lag (number of messages behind).
     */
    private int kafkaConsumerLag;

    /**
     * Network inbound rate (bytes/sec).
     */
    private double networkInboundBytesPerSec;

    /**
     * Network outbound rate (bytes/sec).
     */
    private double networkOutboundBytesPerSec;

    /**
     * Whether this node is healthy and reachable.
     */
    @Builder.Default
    private boolean healthy =  true;

    /**
     * Gets total network bandwidth (inbound + outbound) in bytes/sec.
     *
     * @return total bandwidth
     */
    public double getTotalNetworkBandwidth() {
        return networkInboundBytesPerSec + networkOutboundBytesPerSec;
    }

    /**
     * Gets total network bandwidth in MB/sec.
     *
     * @return bandwidth in MB/sec
     */
    public double getTotalNetworkBandwidthMBps() {
        return getTotalNetworkBandwidth() / 1024 / 1024;
    }

    /**
     * Checks if this node needs scaling based on thresholds.
     *
     * @param connPerPod connection capacity per pod
     * @param mpsPerPod message capacity per pod
     * @param lSloMs latency SLO in milliseconds
     * @return true if any metric exceeds threshold
     */
    public boolean needsScaling(int connPerPod, int mpsPerPod, double lSloMs) {
        return activeConn > connPerPod * 0.8 ||
               mps > mpsPerPod * 0.8 ||
               p95LatencyMs > lSloMs ||
               cpu > 0.8 ||
               mem > 0.8 ||
               kafkaConsumerLag > 1000;
    }

    @Override
    public String toString() {
        return String.format(
                "NodeMetrics{nodeId='%s', conn=%d, mps=%.2f, p95Lat=%.2fms, cpu=%.1f%%, mem=%.1f%%, kafkaLag=%d, healthy=%s}",
                nodeId, activeConn, mps, p95LatencyMs, cpu * 100, mem * 100, kafkaConsumerLag, healthy
        );
    }
}
