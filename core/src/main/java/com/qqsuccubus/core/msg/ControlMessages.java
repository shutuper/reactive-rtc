package com.qqsuccubus.core.msg;

import com.qqsuccubus.core.model.DistributionVersion;
import lombok.Builder;
import lombok.Value;
import lombok.With;

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
        DistributionVersion version;

        /**
         * Human-readable reason for this update (e.g., "node-3 joined").
         */
        String reason;

        /**
         * Timestamp when this update was issued (epoch millis).
         */
        long ts;
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
        String nodeId;

        /**
         * Deadline by which the node should complete draining (epoch millis).
         */
        long deadline;

        /**
         * Maximum number of connections to disconnect (for load redistribution).
         * If 0 or negative, all connections should drain.
         */
        int maxDisconnects;

        /**
         * Reason for drain (e.g., "scale-in", "rolling-update", "load-redistribution").
         */
        String reason;

        /**
         * Timestamp when this signal was issued (epoch millis).
         */
        long ts;
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
        String reason;

        /**
         * Timestamp when this signal was issued (epoch millis).
         */
        long ts;
    }
}

