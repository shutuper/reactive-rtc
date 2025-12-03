package com.qqsuccubus.loadbalancer.redis;

import com.qqsuccubus.core.msg.ControlMessages;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Interface for Redis operations (Dependency Inversion Principle).
 * <p>
 * Enables testing with mock implementations and future Redis client swaps.
 * </p>
 */
public interface IRedisService {

    Mono<Void> setCurrentRingVersion(ControlMessages.RingUpdate ringUpdate);

    /**
     * Gets the current ring version from Redis.
     * Used by non-leader instances to initialize their ring.
     */
    Mono<ControlMessages.RingUpdate> getCurrentRingVersion();

    Flux<List<String>> subscribeToHeartbeats();

    Mono<String> getClientTargetNodeId(String clientId);

    /**
     * Closes Redis connection.
     */
    void close();
}











