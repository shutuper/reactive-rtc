package com.qqsuccubus.loadbalancer;

import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.http.HttpServer;
import com.qqsuccubus.loadbalancer.kafka.KafkaPublisher;
import com.qqsuccubus.loadbalancer.metrics.PrometheusMetricsExporter;
import com.qqsuccubus.loadbalancer.redis.RedisService;
import com.qqsuccubus.loadbalancer.ring.LoadBalancer;
import com.qqsuccubus.loadbalancer.scale.LoadRedistributor;
import com.qqsuccubus.loadbalancer.scale.ScalingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

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
        PrometheusMetricsExporter metricsExporter = new PrometheusMetricsExporter(config.getNodeId());

        // Initialize components
        RedisService redisService = new RedisService(config);
        KafkaPublisher kafkaPublisher = new KafkaPublisher(config);
        LoadBalancer loadBalancer = new LoadBalancer(config, kafkaPublisher, metricsExporter.getRegistry());
        ScalingEngine scalingEngine = new ScalingEngine(config, kafkaPublisher, metricsExporter.getRegistry());

        // Start load redistributor for graceful load balancing
        LoadRedistributor loadRedistributor = new LoadRedistributor(loadBalancer, kafkaPublisher, config);
        loadRedistributor.start().subscribe(
                v -> {},
                err -> log.error("Load redistributor error", err)
        );
        log.info("Load redistributor started (checking every 5 minutes)");

        // Start HTTP server
        HttpServer httpServer = new HttpServer(config, loadBalancer, scalingEngine, metricsExporter);

        httpServer.start()
                .doOnNext(port -> log.info("HTTP server started on port {}", port))
                .doOnError(err -> log.error("Failed to start HTTP server", err))
                .block();

        Disposable heartbeats = redisService.subscribeToHeartbeats(loadBalancer::processHeartbeat);

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            heartbeats.dispose();

            kafkaPublisher.close();

            httpServer.stop();
            log.info("Shutdown complete");
        }));

        log.info("Load-Balancer is ready");
    }
}

