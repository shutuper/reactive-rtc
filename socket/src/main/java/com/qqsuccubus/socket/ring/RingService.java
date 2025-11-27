package com.qqsuccubus.socket.ring;

import com.qqsuccubus.core.hash.SkeletonWeightedRendezvousHash;
import com.qqsuccubus.core.model.DistributionVersion;
import com.qqsuccubus.core.msg.ControlMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for maintaining consistent hash ring state on socket nodes.
 * <p>
 * Responsibilities:
 * - Maintain local copy of the consistent hash ring
 * - Update ring when receiving RingUpdate messages from load balancer
 * - Resolve client IDs to target node IDs using consistent hashing
 * </p>
 */
public class RingService {
    private static final Logger log = LoggerFactory.getLogger(RingService.class);

    private final AtomicReference<RingState> stateRef = new AtomicReference<>(
        new RingState(null, null, Collections.emptyMap())
    );

    /**
     * Updates the ring with a new RingUpdate from the load balancer.
     *
     * @param ringUpdate Ring update message containing new weights and version
     */
    public void updateRing(ControlMessages.RingUpdate ringUpdate) {
        if (ringUpdate == null || ringUpdate.getNodeWeights() == null) {
            log.warn("Received null or invalid ring update");
            return;
        }

        Map<String, Integer> nodeWeights = ringUpdate.getNodeWeights();
        DistributionVersion version = ringUpdate.getVersion();

        if (nodeWeights.isEmpty()) {
            log.warn("Received ring update with empty node weights");
            return;
        }

        // Create new hash ring with updated weights
        SkeletonWeightedRendezvousHash newHash = new SkeletonWeightedRendezvousHash(nodeWeights);
        RingState newState = new RingState(newHash, version, nodeWeights);

        RingState oldState = stateRef.getAndSet(newState);

        log.info("Ring updated: version={}, nodes={}, weights={}, reason={}",
            version.getVersion(), nodeWeights.size(), nodeWeights, ringUpdate.getReason());

        if (oldState.version != null) {
            log.debug("Previous ring version: {}", oldState.version.getVersion());
        }
    }

    /**
     * Resolves a client ID to the target node ID using consistent hashing.
     * <p>
     * This method uses the current ring state to determine which node
     * should handle messages for the given client.
     * </p>
     *
     * @param clientId Client identifier
     * @return Target node ID, or null if ring is not initialized
     */
    public String resolveTargetNode(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            log.warn("Cannot resolve null or empty clientId");
            return null;
        }

        RingState state = stateRef.get();
        if (state.hash == null) {
            log.warn("Ring not initialized yet, cannot resolve node for client: {}", clientId);
            return null;
        }

        try {
            String targetNode = state.hash.selectNode(clientId);
            log.debug("Resolved client {} to node {}", clientId, targetNode);
            return targetNode;
        } catch (IllegalStateException e) {
            log.error("Failed to resolve node for client {}: {}", clientId, e.getMessage());
            return null;
        }
    }

    /**
     * Gets the current ring version.
     *
     * @return Current version, or null if not initialized
     */
    public DistributionVersion getCurrentVersion() {
        return stateRef.get().version;
    }

    /**
     * Gets the current node weights.
     *
     * @return Map of nodeId -> weight
     */
    public Map<String, Integer> getCurrentWeights() {
        return Collections.unmodifiableMap(stateRef.get().nodeWeights);
    }

    /**
     * Checks if the ring is initialized.
     *
     * @return true if ring has been initialized with at least one update
     */
    public boolean isInitialized() {
        return stateRef.get().hash != null;
    }

    /**
     * Immutable snapshot of ring state.
     */
    private record RingState(
        SkeletonWeightedRendezvousHash hash,
        DistributionVersion version,
        Map<String, Integer> nodeWeights
    ) {
    }
}

