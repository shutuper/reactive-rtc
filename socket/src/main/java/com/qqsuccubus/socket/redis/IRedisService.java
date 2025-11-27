package com.qqsuccubus.socket.redis;

import com.qqsuccubus.core.msg.ControlMessages;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Interface for Redis operations (Dependency Inversion Principle).
 * <p>
 * Enables testing with mock implementations and future Redis client swaps.
 * </p>
 */
public interface IRedisService {
    /**
     * Saves session metadata for a user.
     */
    Mono<Void> saveSession(String userId, String nodeId, long lastOffset);

    /**
     * Retrieves session metadata for a user.
     */
    Mono<Map<String, String>> getSession(String userId);

    /**
     * Deletes session metadata for a user.
     */
    Mono<Void> deleteSession(String userId);

    /**
     * Appends a message to the user's buffer.
     */
    Mono<Void> appendToBuffer(String userId, String message);

    /**
     * Retrieves buffered messages for resume.
     */
    Flux<String> getBufferedMessages(String userId, int count);

    /**
     * Closes Redis connection.
     */
    void close();

    Mono<ControlMessages.RingUpdate> getCurrentRingVersion();
}











