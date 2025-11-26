package com.qqsuccubus.loadbalancer.redis;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Interface for Redis operations (Dependency Inversion Principle).
 * <p>
 * Enables testing with mock implementations and future Redis client swaps.
 * </p>
 */
public interface IRedisService {

    Flux<List<String>> subscribeToHeartbeats();

    /**
     * Closes Redis connection.
     */
    void close();
}











