package com.qqsuccubus.socket.session;

import com.qqsuccubus.core.msg.Envelope;
import com.qqsuccubus.core.util.JsonUtils;
import com.qqsuccubus.socket.redis.IRedisService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service for buffering and replaying messages (Single Responsibility Principle).
 * <p>
 * Handles Redis buffering logic separately from session management.
 * </p>
 */
public class MessageBufferService {

    private final IRedisService redisService;
    private final int bufferMax;

    public MessageBufferService(IRedisService redisService, int bufferMax) {
        this.redisService = redisService;
        this.bufferMax = bufferMax;
    }

    /**
     * Buffers a message for potential replay.
     *
     * @param envelope Message envelope
     * @return Mono completing when buffered
     */
    public Mono<Void> bufferMessage(Envelope envelope) {
        String json = JsonUtils.writeValueAsString(envelope);
        return redisService.appendToBuffer(envelope.getToClientId(), json);
    }

    /**
     * Retrieves buffered messages for resume replay.
     *
     * @param userId User identifier
     * @return Flux of Envelope
     */
    public Flux<Envelope> getBufferedMessages(String userId) {
        return redisService.getBufferedMessages(userId, bufferMax)
            .mapNotNull(json -> JsonUtils.readValue(json, Envelope.class));
    }
}











