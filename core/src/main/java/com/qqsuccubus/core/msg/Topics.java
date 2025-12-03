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

    public static final String FORWARD_VIA_LB = "rtc.lb.forward";

    /**
     * Prefix for per-node delivery topics.
     * <p>
     * Topic naming convention: delivery_node_{nodeId}
     * Example: delivery_node_socket-abc123
     * </p>
     * <p>
     * Each socket node has its own delivery topic for receiving messages
     * destined to clients connected to that node.
     * </p>
     */
    public static final String DELIVERY_TOPIC_PREFIX = "delivery_node_";

    /**
     * Generates the delivery topic name for a given node.
     *
     * @param nodeId Node identifier
     * @return Topic name: delivery_node_{nodeId}
     */
    public static String deliveryTopicFor(String nodeId) {
        return DELIVERY_TOPIC_PREFIX + nodeId;
    }

}

