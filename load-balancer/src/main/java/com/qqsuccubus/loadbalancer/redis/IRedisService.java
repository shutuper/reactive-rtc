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

    Flux<List<String>> subscribeToHeartbeats();

    /**
     * Closes Redis connection.
     */
    void close();
}











