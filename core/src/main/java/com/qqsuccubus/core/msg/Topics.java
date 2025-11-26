package com.qqsuccubus.core.msg;

public final class Topics {
    private Topics() {
    }

    /**
     * Control topic for ring updates (RingUpdate messages).
     * Published by load-balancer, consumed by all socket nodes.
     */
    public static final String CONTROL_RING = "rtc.control.ring";

    /**
     * Control topic for scaling signals (ScaleSignal messages).
     * Published by load-balancer, consumed by autoscalers.
     */
    public static final String CONTROL_SCALE = "rtc.control.scale";

    /**
     * Control topic for drain signals (DrainSignal messages).
     * Published by load-balancer, consumed by socket nodes for graceful disconnections.
     */
    public static final String CONTROL_DRAIN = "rtc.control.drain";

}

