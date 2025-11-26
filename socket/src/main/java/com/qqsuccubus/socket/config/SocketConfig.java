package com.qqsuccubus.socket.config;

import lombok.Builder;
import lombok.Value;

/**
 * Configuration for Socket node, loaded from environment variables.
 */
@Value
@Builder(toBuilder = true)
public class SocketConfig {

    String nodeId;
    int httpPort;
    String kafkaBootstrap;
    String redisUrl;
    int bufferMax;
    int resumeTtlSec;
    int perConnBufferSize;
    int pingInterval;
    int idleTimeout;
    boolean useVirtualThreads;

    public static SocketConfig fromEnv() {
        return SocketConfig.builder()
                .nodeId(getEnv("NODE_ID", "socket-node-1"))
                .httpPort(Integer.parseInt(getEnv("HTTP_PORT", "8080")))
                .kafkaBootstrap(getEnv("KAFKA_BOOTSTRAP", "localhost:9092"))
                .redisUrl(getEnv("REDIS_URL", "redis://localhost:6379"))
                .bufferMax(Integer.parseInt(getEnv("BUFFER_MAX", "100")))
                .resumeTtlSec(Integer.parseInt(getEnv("RESUME_TTL_SEC", "3600")))
                .perConnBufferSize(Integer.parseInt(getEnv("PER_CONN_BUFFER_SIZE", "100")))
                .pingInterval(Integer.parseInt(getEnv("PING_INTERVAL", "10")))
                .idleTimeout(Integer.parseInt(getEnv("IDLE_TIMEOUT", "20")))
                .useVirtualThreads(Boolean.parseBoolean(getEnv("useVirtualThreads", "true")))
                .build();
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
}







