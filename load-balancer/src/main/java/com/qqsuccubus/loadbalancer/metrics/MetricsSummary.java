package com.qqsuccubus.loadbalancer.metrics;

import lombok.Builder;
import lombok.Data;

/**
 * Aggregated metrics summary for autoscaling decisions.
 * Contains key metrics from all socket nodes.
 */
@Data
@Builder
public class MetricsSummary {
    /**
     * Total message throughput across all nodes (messages/sec).
     */
    private double totalThroughput;

    /**
     * p95 latency across all nodes (seconds).
     */
    private double p95LatencySeconds;

    /**
     * Total active WebSocket connections across all nodes.
     */
    private int totalConnections;

    /**
     * Total Kafka consumer lag across all socket nodes (messages).
     */
    private int totalConsumerLag;

    /**
     * Number of socket nodes currently up.
     */
    private int nodeCount;

    /**
     * Gets p95 latency in milliseconds.
     *
     * @return p95 latency in milliseconds
     */
    public double getP95LatencyMs() {
        return p95LatencySeconds * 1000.0;
    }

    /**
     * Gets average throughput per node (messages/sec).
     *
     * @return Average messages per second per node
     */
    public double getThroughputPerNode() {
        return nodeCount > 0 ? totalThroughput / nodeCount : 0.0;
    }

    /**
     * Gets average connections per node.
     *
     * @return Average connections per node
     */
    public double getConnectionsPerNode() {
        return nodeCount > 0 ? (double) totalConnections / nodeCount : 0.0;
    }

    /**
     * Gets average consumer lag per node.
     *
     * @return Average lag per node
     */
    public double getLagPerNode() {
        return nodeCount > 0 ? (double) totalConsumerLag / nodeCount : 0.0;
    }

    /**
     * Checks if latency exceeds SLO threshold.
     *
     * @param sloMs SLO threshold in milliseconds
     * @return true if latency exceeds SLO
     */
    public boolean isLatencyAboveSlo(double sloMs) {
        return getP95LatencyMs() > sloMs;
    }

    /**
     * Checks if throughput per node exceeds capacity.
     *
     * @param mpsPerPod Expected messages per second per pod
     * @return true if throughput exceeds capacity
     */
    public boolean isThroughputAboveCapacity(int mpsPerPod) {
        return getThroughputPerNode() > mpsPerPod;
    }

    /**
     * Checks if connections per node exceed capacity.
     *
     * @param connPerPod Expected connections per pod
     * @return true if connections exceed capacity
     */
    public boolean areConnectionsAboveCapacity(int connPerPod) {
        return getConnectionsPerNode() > connPerPod;
    }

    /**
     * Checks if consumer lag is high.
     *
     * @param lagThreshold Lag threshold
     * @return true if lag is high
     */
    public boolean isLagHigh(int lagThreshold) {
        return totalConsumerLag > lagThreshold;
    }

    @Override
    public String toString() {
        return String.format(
                "MetricsSummary{throughput=%.2f msg/s (%.2f/node), p95Latency=%.2fms, " +
                "connections=%d (%.1f/node), kafkaLag=%d (%.1f/node), nodes=%d}",
                totalThroughput, getThroughputPerNode(), getP95LatencyMs(),
                totalConnections, getConnectionsPerNode(),
                totalConsumerLag, getLagPerNode(), nodeCount
        );
    }
}






















