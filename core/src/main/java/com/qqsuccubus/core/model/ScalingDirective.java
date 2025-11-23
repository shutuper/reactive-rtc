package com.qqsuccubus.core.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Directive for cluster scaling decisions.
 * <p>
 * The load-balancer computes this based on domain metrics (connections, mps, p95 latency)
 * and publishes it to the control plane. Can be consumed by autoscalers (HPA, KEDA, custom operators).
 * </p>
 */
@Value
@Builder(toBuilder = true)
@With
public class ScalingDirective {
    /**
     * Scaling action to take.
     */
    Action action;

    /**
     * Optional target replica count (if action is SCALE_OUT or SCALE_IN).
     */
    Integer targetReplicas;

    /**
     * Human-readable reason for this directive.
     */
    String reason;

    /**
     * Timestamp when this directive was computed.
     */
    long timestampMs;

    /**
     * Scaling action enumeration.
     */
    public enum Action {
        /**
         * No scaling needed; cluster is within SLO.
         */
        NONE,

        /**
         * Scale out (add more replicas).
         */
        SCALE_OUT,

        /**
         * Scale in (remove replicas).
         */
        SCALE_IN
    }
}


