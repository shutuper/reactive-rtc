package com.qqsuccubus.loadbalancer;

import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.http.HttpServer;
import com.qqsuccubus.loadbalancer.kafka.KafkaPublisher;
import com.qqsuccubus.loadbalancer.ring.RingManager;
import com.qqsuccubus.loadbalancer.scale.LoadRedistributor;
import com.qqsuccubus.loadbalancer.scale.ScalingEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Load-Balancer control plane.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Track node heartbeats and compute weighted consistent hash ring</li>
 *   <li>Publish ring updates to Kafka</li>
 *   <li>Provide resolution endpoints for frontends</li>
 *   <li>Compute scaling directives based on domain metrics</li>
 *   <li>Expose /metrics endpoint for Prometheus</li>
 * </ul>
 * </p>
 */
public class LoadBalancerApp {
    private static final Logger log = LoggerFactory.getLogger(LoadBalancerApp.class);

    public static void main(String[] args) {
        LBConfig config = LBConfig.fromEnv();

        log.info("Starting Load-Balancer");
        log.info("  Kafka: {}", config.getKafkaBootstrap());
        log.info("  Ring vnodes per weight: {}", config.getRingVnodesPerWeight());
        log.info("  Public domain template: {}", config.getPublicDomainTemplate());

        // Setup metrics
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        // Initialize components
        KafkaPublisher kafkaPublisher = new KafkaPublisher(config);
        RingManager ringManager = new RingManager(config, kafkaPublisher, meterRegistry);
        ScalingEngine scalingEngine = new ScalingEngine(config, kafkaPublisher, meterRegistry);

        // Start load redistributor for graceful load balancing
        LoadRedistributor loadRedistributor = new LoadRedistributor(ringManager, kafkaPublisher, config);
        loadRedistributor.start().subscribe(
                v -> {},
                err -> log.error("Load redistributor error", err)
        );
        log.info("Load redistributor started (checking every 5 minutes)");

        // Start HTTP server
        HttpServer httpServer = new HttpServer(config, ringManager, scalingEngine, meterRegistry);
        httpServer.start()
                .doOnNext(port -> log.info("HTTP server started on port {}", port))
                .doOnError(err -> log.error("Failed to start HTTP server", err))
                .block();

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            kafkaPublisher.close();
            log.info("Shutdown complete");
        }));

        log.info("Load-Balancer is ready");
    }
}

