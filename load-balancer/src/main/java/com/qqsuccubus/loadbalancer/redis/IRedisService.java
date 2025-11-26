package com.qqsuccubus.loadbalancer.redis;

import reactor.core.Disposable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Interface for Redis operations (Dependency Inversion Principle).
 * <p>
 * Enables testing with mock implementations and future Redis client swaps.
 * </p>
 */
public interface IRedisService {

    Disposable subscribeToHeartbeats(Consumer<List<String>> handleActiveNodesHeartbeat);

    /**
     * Closes Redis connection.
     */
    void close();
}











