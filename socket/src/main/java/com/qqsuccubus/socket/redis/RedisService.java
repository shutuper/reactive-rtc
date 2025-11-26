package com.qqsuccubus.socket.redis;

import com.qqsuccubus.core.redis.Keys;
import com.qqsuccubus.socket.config.SocketConfig;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Reactive Redis service for session management and message buffering.
 * <p>
 * All operations are non-blocking using Lettuce reactive API.
 * Implements IRedisService for dependency inversion.
 * </p>
 */
public class RedisService implements IRedisService {
    private static final Logger log = LoggerFactory.getLogger(RedisService.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisReactiveCommands<String, String> commands;
    private final SocketConfig config;

    public RedisService(SocketConfig config) {
        this.config = config;
        this.client = RedisClient.create(config.getRedisUrl());
        this.connection = client.connect();
        this.commands = connection.reactive();
        log.info("Connected to Redis: {}", config.getRedisUrl());
    }

    public Disposable startHeartbeats() {
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(10))
            .flatMap(tick -> commands.hset(Keys.heartbeats(), Map.of(
                config.getNodeId(), String.valueOf(System.currentTimeMillis())
            )))
            .thenMany(commands.hexpire(Keys.heartbeats(), 20, config.getNodeId()))
            .subscribe();
    }

    /**
     * Saves session metadata for a user.
     *
     * @param clientId   User identifier
     * @param nodeId     Current owner node
     * @param lastOffset Last acknowledged offset
     * @return Mono completing when saved
     */
    public Mono<Void> saveSession(String clientId, String nodeId, long lastOffset) {
        String key = Keys.sess(clientId);
        Map<String, String> sessionData = new HashMap<>();
        sessionData.put("nodeId", nodeId);
        sessionData.put("lastOffset", String.valueOf(lastOffset));
        sessionData.put("lastSeen", String.valueOf(System.currentTimeMillis()));

        return commands.hmset(key, sessionData)
            .then(commands.expire(key, config.getResumeTtlSec()))
            .then()
            .doOnError(err -> log.error("Failed to save session for {}", clientId, err));
    }

    /**
     * Retrieves session metadata for a user.
     *
     * @param clientId User identifier
     * @return Mono of session map, or empty if not found
     */
    public Mono<Map<String, String>> getSession(String clientId) {
        return commands.hgetall(Keys.sess(clientId))
            .collectMap(KeyValue::getKey, Value::getValue)
            .doOnError(err -> log.error("Failed to get session for {}", clientId, err));
    }

    /**
     * Deletes session metadata for a user.
     *
     * @param clientId User identifier
     * @return Mono completing when deleted
     */
    public Mono<Void> deleteSession(String clientId) {
        return commands.del(Keys.sess(clientId))
            .then()
            .doOnError(err -> log.error("Failed to delete session for {}", clientId, err));
    }

    /**
     * Appends a message to the user's buffer using Redis Streams (XADD).
     * <p>
     * Redis Streams provide:
     * <ul>
     *   <li>Automatic message IDs with timestamp ordering</li>
     *   <li>Better replay semantics with XRANGE/XREAD</li>
     *   <li>Built-in trimming with MAXLEN and MINID</li>
     *   <li>Consumer group support for acknowledgments</li>
     * </ul>
     * </p>
     * <p>
     * Messages are trimmed both by count (MAXLEN) and by age (MINID based on TTL).
     * </p>
     *
     * @param clientId User identifier
     * @param message  Serialized message (JSON)
     * @return Mono completing when appended
     */
    public Mono<Void> appendToBuffer(String clientId, String message) {
        String streamKey = Keys.buf(clientId);

        // Calculate minimum message ID based on TTL (messages older than this should be trimmed)
        long ttlMillis = config.getResumeTtlSec() * 1000L;
        long minTimestamp = System.currentTimeMillis() - ttlMillis;
        String minId = minTimestamp + "-0";  // Redis Stream ID format: <timestamp>-<sequence>

        // XADD with both MAXLEN and trim old messages
        XAddArgs args = XAddArgs.Builder
            .maxlen(config.getBufferMax())  // Trim to max buffer size
            .approximateTrimming();   // Use approximate trimming (~) for better performance

        // Add message to stream with a single field "msg"
        return commands.xadd(streamKey, args, Map.of("msg", message))
            .then(commands.xtrim(streamKey, XTrimArgs.Builder.minId(minId).approximateTrimming()))
            .then(commands.expire(streamKey, config.getResumeTtlSec()))
            .then()
            .doOnSuccess(v -> log.info("Appended message to stream for client {} and trimmed old messages", clientId))
            .doOnError(err -> log.error("Failed to append to buffer stream for {}", clientId, err));
    }

    /**
     * Retrieves buffered messages for resume using Redis Streams (XRANGE) and removes them.
     * <p>
     * Uses XRANGE to read messages from the stream in chronological order.
     * Messages are filtered by age (TTL) and immediately deleted after reading.
     * </p>
     * <p>
     * Message expiration strategy:
     * <ul>
     *   <li>Messages older than TTL are filtered out based on stream ID timestamp</li>
     *   <li>All read messages are deleted immediately after retrieval</li>
     *   <li>Entire stream is deleted after successful replay</li>
     * </ul>
     * </p>
     *
     * @param clientId User identifier
     * @param count    Number of messages to retrieve (currently retrieves all, limited by buffer max)
     * @return Flux of messages in chronological order (oldest first)
     */
    public Flux<String> getBufferedMessages(String clientId, int count) {
        String streamKey = Keys.buf(clientId);

        // Calculate minimum message ID based on TTL
        long ttlMillis = config.getResumeTtlSec() * 1000L;
        long minTimestamp = System.currentTimeMillis() - ttlMillis;
        String minId = minTimestamp + "-0";

        // Get messages and delete after reading in one transaction
        return commands.xrange(streamKey, Range.<String>unbounded().gte(minId), Limit.from(count))
            .collectList()
            .flatMapMany(messages -> {
                if (messages.isEmpty()) {
                    return Flux.empty();
                }

                String[] messageIds = messages.stream()
                    .map(StreamMessage::getId)
                    .toArray(String[]::new);

                return commands.xdel(streamKey, messageIds)
                    .thenMany(Flux.fromIterable(messages))
                    .map(message -> message.getBody().get("msg"));
            })
            .doOnError(err -> log.error("Failed to get buffered messages for {}", clientId, err));
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
        log.info("Redis connection closed");
    }
}

