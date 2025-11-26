package com.qqsuccubus.core.redis;

/**
 * Redis keyspace definitions for session management and message buffering.
 * <p>
 * <b>Key design principles:</b>
 * <ul>
 *   <li>Use namespace prefixes to avoid collisions (sess:, buf:, node:)</li>
 *   <li>Set appropriate TTLs to prevent unbounded growth</li>
 *   <li>Use hashes for structured data, streams/lists for message buffers</li>
 * </ul>
 * </p>
 */
public final class Keys {
    private Keys() {
    }

    /**
     * Session key for a user: {@code sess:{clientId}}
     * <p>
     * <b>Type:</b> Hash
     * <br>
     * <b>Fields:</b>
     * <ul>
     *   <li>{@code nodeId}: Current owner node</li>
     *   <li>{@code connId}: Connection identifier (UUID)</li>
     *   <li>{@code lastOffset}: Last acknowledged message offset</li>
     *   <li>{@code lastSeen}: Last activity timestamp (epoch millis)</li>
     * </ul>
     * <b>TTL:</b> Sliding window (e.g., 1 hour); refreshed on activity.
     * </p>
     *
     * @param clientId User identifier
     * @return Redis key
     */
    public static String sess(String clientId) {
        return "sess:" + clientId;
    }

    public static String heartbeats() {
        return "heartbeats";
    }

    /**
     * Message buffer key for a user: {@code buf:{clientId}}
     * <p>
     * <b>Type:</b> Stream (XADD with MAXLEN ~ W) or List (capped)
     * <br>
     * <b>Content:</b> Serialized Envelope objects
     * <br>
     * <b>Retention:</b> Last W messages (e.g., W=100) or time-based TTL
     * <br>
     * <b>Usage:</b> Resume replay; client requests messages from {@code lastOffset+1}
     * </p>
     *
     * @param clientId User identifier
     * @return Redis key
     */
    public static String buf(String clientId) {
        return "buf:" + clientId;
    }

    /**
     * Node connection gauge: {@code node:{nodeId}:conn}
     * <p>
     * <b>Type:</b> String (integer)
     * <br>
     * <b>Content:</b> Current number of active connections on this node
     * <br>
     * <b>Usage:</b> Monitoring, capacity planning
     * </p>
     *
     * @param nodeId Node identifier
     * @return Redis key
     */
    public static String nodeConn(String nodeId) {
        return "node:" + nodeId + ":conn";
    }

}











