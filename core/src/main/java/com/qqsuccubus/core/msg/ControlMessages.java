package com.qqsuccubus.core.msg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qqsuccubus.core.model.DistributionVersion;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.Map;

/**
 * Control messages exchanged between load-balancer and socket nodes.
 * <p>
 * These messages flow over Kafka control topics and trigger topology changes,
 * graceful drains, and scaling actions.
 * </p>
 */
public final class ControlMessages {
    private ControlMessages() {
    }

    /**
     * Published by load-balancer when the ring topology changes.
     * Socket nodes consume this and update their routing tables.
     */
    @Value
    @Builder(toBuilder = true)
    @With
    public static class RingUpdate {
        /**
         * New distribution version.
         */
        @JsonProperty("version")
        DistributionVersion version;

        /**
         * Node weights for consistent hashing.
         * Map of nodeId -> weight (integer, typically 1-500, sum should be 100 * numNodes)
         */
        @JsonProperty("nodeWeights")
        Map<String, Integer> nodeWeights;

        /**
         * Human-readable reason for this update (e.g., "node-3 joined").
         */
        @JsonProperty("reason")
        String reason;

        /**
         * Timestamp when this update was issued (epoch millis).
         */
        @JsonProperty("ts")
        long ts;

        @JsonCreator
        public RingUpdate(
            @JsonProperty("version") DistributionVersion version,
            @JsonProperty("nodeWeights") Map<String, Integer> nodeWeights,
            @JsonProperty("reason") String reason,
            @JsonProperty("ts") long ts
        ) {
            this.version = version;
            this.nodeWeights = nodeWeights;
            this.reason = reason;
            this.ts = ts;
        }
    }

    /**
     * Issued by load-balancer to signal a node to gracefully drain and prepare for shutdown.
     * <p>
     * The node should:
     * <ul>
     *   <li>Stop accepting new connections</li>
     *   <li>Send Close(1012) with jittered delay to existing clients</li>
     *   <li>Provide resumeToken before closing</li>
     *   <li>Flush buffers to Redis</li>
     * </ul>
     * </p>
     */
    @Value
    @Builder(toBuilder = true)
    @With
    public static class DrainSignal {
        /**
         * Target node ID to drain.
         */
        @JsonProperty("nodeId")
        String nodeId;

        /**
         * Deadline by which the node should complete draining (epoch millis).
         */
        @JsonProperty("deadline")
        long deadline;

        /**
         * Maximum number of connections to disconnect (for load redistribution).
         * If 0 or negative, all connections should drain.
         */
        @JsonProperty("maxDisconnects")
        int maxDisconnects;

        /**
         * Reason for drain (e.g., "scale-in", "rolling-update", "load-redistribution").
         */
        @JsonProperty("reason")
        String reason;

        /**
         * Timestamp when this signal was issued (epoch millis).
         */
        @JsonProperty("ts")
        long ts;

        @JsonCreator
        public DrainSignal(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("deadline") long deadline,
            @JsonProperty("maxDisconnects") int maxDisconnects,
            @JsonProperty("reason") String reason,
            @JsonProperty("ts") long ts
        ) {
            this.nodeId = nodeId;
            this.deadline = deadline;
            this.maxDisconnects = maxDisconnects;
            this.reason = reason;
            this.ts = ts;
        }
    }

    /**
     * Published by load-balancer to signal scaling actions.
     * Can be consumed by autoscalers (HPA, KEDA, custom operators).
     */
    @Value
    @Builder(toBuilder = true)
    @With
    public static class ScaleSignal {

        /**
         * Reason for this scaling decision.
         */
        @JsonProperty("reason")
        String reason;

        /**
         * Timestamp when this signal was issued (epoch millis).
         */
        @JsonProperty("ts")
        long ts;

        @JsonCreator
        public ScaleSignal(
            @JsonProperty("reason") String reason,
            @JsonProperty("ts") long ts
        ) {
            this.reason = reason;
            this.ts = ts;
        }
    }
}

