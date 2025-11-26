package com.qqsuccubus.loadbalancer.config;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * Configuration for Load-Balancer, loaded from environment variables.
 */
@Value
@Builder(toBuilder = true)
public class LBConfig {

    String nodeId;
    int httpPort;
    String kafkaBootstrap;
    String redisUrl;

    // Scaling parameters
    double alpha;          // connection weight
    double beta;           // mps weight
    double gamma;          // latency weight base
    double delta;          // latency weight exponent
    double lSloMs;         // latency SLO in milliseconds
    Duration scalingInterval;
    int maxScaleStep;      // max replicas to add/remove per scaling decision

    // Heartbeat grace window
    Duration heartbeatGrace;

    // Prometheus configuration
    String prometheusHost;
    int prometheusPort;

    public static LBConfig fromEnv() {
        return LBConfig.builder()
            .nodeId(getEnv("NODE_ID", "lb-node-1"))
            .httpPort(Integer.parseInt(getEnv("HTTP_PORT", "8081")))
            .kafkaBootstrap(getEnv("KAFKA_BOOTSTRAP", "localhost:9092"))
            .redisUrl(getEnv("REDIS_URL", "redis://localhost:6379"))
            .alpha(Double.parseDouble(getEnv("ALPHA", "0.4")))
            .beta(Double.parseDouble(getEnv("BETA", "0.4")))
            .gamma(Double.parseDouble(getEnv("GAMMA", "0.2")))
            .delta(Double.parseDouble(getEnv("DELTA", "2.0")))
            .lSloMs(Double.parseDouble(getEnv("L_SLO_MS", "500.0")))
            .scalingInterval(Duration.ofSeconds(Integer.parseInt(getEnv("SCALING_INTERVAL_SEC", "30"))))
            .maxScaleStep(Integer.parseInt(getEnv("MAX_SCALE_STEP", "3")))
            .heartbeatGrace(Duration.ofSeconds(Integer.parseInt(getEnv("HEARTBEAT_GRACE_SEC", "90"))))
            .prometheusHost(getEnv("PROMETHEUS_HOST", "prometheus"))
            .prometheusPort(Integer.parseInt(getEnv("PROMETHEUS_PORT", "9090")))
            .build();
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
}











