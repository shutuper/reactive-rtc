package com.qqsuccubus.loadbalancer.ring;

public interface ILoadBalancer {

    /**
     * Resolves a userId to a node.
     */
    LoadBalancer.NodeEntry resolveNode(String userId);

}

