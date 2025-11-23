package com.qqsuccubus.loadbalancer.ring;

import com.qqsuccubus.core.model.Heartbeat;
import com.qqsuccubus.core.model.NodeDescriptor;
import com.qqsuccubus.core.model.RingSnapshot;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Interface for ring management (Dependency Inversion Principle).
 * <p>
 * Abstracts ring computation and node tracking.
 * </p>
 */
public interface IRingManager {
    /**
     * Processes a heartbeat from a socket node.
     */
    Mono<Void> processHeartbeat(Heartbeat heartbeat);

    /**
     * Resolves a userId to a node.
     */
    NodeDescriptor resolveNode(String userId);

    /**
     * Gets the current ring snapshot.
     */
    RingSnapshot getRingSnapshot();

    /**
     * Gets all active nodes.
     */
    Map<String, RingManager.NodeEntry> getActiveNodes();

    /**
     * Gets the number of physical nodes in the ring.
     */
    int getPhysicalNodeCount();
}

