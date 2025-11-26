package com.qqsuccubus.loadbalancer.ring;

import java.util.Map;

/**
 * Interface for ring management (Dependency Inversion Principle).
 * <p>
 * Abstracts ring computation and node tracking.
 * </p>
 */
public interface ILoadBalancer {

    /**
     * Resolves a userId to a node.
     */
    LoadBalancer.NodeEntry resolveNode(String userId);

    /**
     * Gets all active nodes.
     */
    Map<String, LoadBalancer.NodeEntry> getActiveNodes();

}

