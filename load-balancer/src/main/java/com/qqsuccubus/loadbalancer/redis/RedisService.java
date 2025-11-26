package com.qqsuccubus.loadbalancer.redis;

import com.qqsuccubus.core.redis.Keys;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

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

    public RedisService(LBConfig config) {
        this.client = RedisClient.create(config.getRedisUrl());
        this.connection = client.connect();
        this.commands = connection.reactive();
        log.info("Connected to Redis: {}", config.getRedisUrl());
    }

    public Flux<List<String>> subscribeToHeartbeats() {
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(20))
            .flatMap(tick -> commands.hgetall(Keys.heartbeats())
                .map(KeyValue::getKey)
                .collectList() // Collects keys for this specific tick only
            );
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
        log.info("Redis connection closed");
    }
}

