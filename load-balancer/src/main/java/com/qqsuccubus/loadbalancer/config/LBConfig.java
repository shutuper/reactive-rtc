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
    int httpPort;
    String kafkaBootstrap;
    String publicDomainTemplate;
    int ringVnodesPerWeight;
    String ringSecret;

    // Scaling parameters
    double alpha;          // connection weight
    double beta;           // mps weight
    double gamma;          // latency weight base
    double delta;          // latency weight exponent
    double lSloMs;         // latency SLO in milliseconds
    int connPerPod;        // expected connections per pod
    int mpsPerPod;         // expected mps per pod
    Duration scalingInterval;
    int maxScaleStep;      // max replicas to add/remove per scaling decision

    // Heartbeat grace window
    Duration heartbeatGrace;

    // Prometheus configuration
    String prometheusHost;
    int prometheusPort;

    public static LBConfig fromEnv() {
        return LBConfig.builder()
                .httpPort(Integer.parseInt(getEnv("HTTP_PORT", "8081")))
                .kafkaBootstrap(getEnv("KAFKA_BOOTSTRAP", "localhost:9092"))
                .publicDomainTemplate(getEnv("PUBLIC_DOMAIN_TEMPLATE", "ws://localhost:%d/ws"))
                .ringVnodesPerWeight(Integer.parseInt(getEnv("RING_VNODES_PER_WEIGHT", "150")))
                .ringSecret(getEnv("RING_SECRET", "changeme-secret-key"))
                .alpha(Double.parseDouble(getEnv("ALPHA", "0.4")))
                .beta(Double.parseDouble(getEnv("BETA", "0.4")))
                .gamma(Double.parseDouble(getEnv("GAMMA", "0.2")))
                .delta(Double.parseDouble(getEnv("DELTA", "2.0")))
                .lSloMs(Double.parseDouble(getEnv("L_SLO_MS", "500.0")))
                .connPerPod(Integer.parseInt(getEnv("CONN_PER_POD", "5000")))
                .mpsPerPod(Integer.parseInt(getEnv("MPS_PER_POD", "2500")))
                .scalingInterval(Duration.ofSeconds(Integer.parseInt(getEnv("SCALING_INTERVAL_SEC", "60"))))
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











