package com.qqsuccubus.socket;

import com.qqsuccubus.socket.config.SocketConfig;
import com.qqsuccubus.socket.http.HttpServer;
import com.qqsuccubus.socket.kafka.KafkaService;
import com.qqsuccubus.socket.metrics.MetricsService;
import com.qqsuccubus.socket.metrics.PrometheusMetricsExporter;
import com.qqsuccubus.socket.redis.RedisService;
import com.qqsuccubus.socket.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.Disposable;

import java.time.Duration;

/**
 * Main entry point for the Socket node.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Serve WebSockets at /ws (query: userId, optional resumeToken)</li>
 *   <li>Maintain session map in Redis</li>
 *   <li>Subscribe to Kafka control topics (ring updates, drain signals)</li>
 *   <li>Two-hop relay for remote users</li>
 *   <li>Graceful reconnect with jitter</li>
 *   <li>Expose /healthz and /metrics endpoints</li>
 * </ul>
 * </p>
 */
public class SocketApp {
    private static final Logger log = LoggerFactory.getLogger(SocketApp.class);

    public static void main(String[] args) {
        SocketConfig config = SocketConfig.fromEnv();
        MDC.put("nodeId", config.getNodeId());

        if (config.isUseVirtualThreads()) {
            System.setProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true");
            log.info("reactor.schedulers.defaultBoundedElasticOnVirtualThreads = true");
        }

        log.info("Starting Socket node: {}", config.getNodeId());
        log.info("  Kafka: {}", config.getKafkaBootstrap());
        log.info("  Redis: {}", config.getRedisUrl());

        // Setup metrics registry with Prometheus support
        PrometheusMetricsExporter metricsExporter = new PrometheusMetricsExporter(config.getNodeId());
        // Initialize services
        RedisService redisService = new RedisService(config);
        MetricsService metricsService = new MetricsService(metricsExporter.getRegistry(), config);
        SessionManager sessionManager = new SessionManager(redisService, config, metricsService);
        KafkaService kafkaService = new KafkaService(
            config, sessionManager, metricsService, sessionManager.getBufferService()
        );

        // Start Kafka consumers (block until initialized)
        kafkaService.start().block();

        // Start HTTP + WebSocket server
        HttpServer httpServer = new HttpServer(
            config,
            sessionManager,
            kafkaService,
            metricsService,
            metricsExporter
        );

        httpServer.start()
            .doOnNext(port -> log.info("HTTP server started on port {}", port))
            .doOnError(err -> log.error("Failed to start HTTP server", err))
            .block();

        Disposable heartbeats = redisService.startHeartbeats();

        log.info("Socket node {} is ready", config.getNodeId());

        handleShutdown(config, sessionManager, kafkaService, httpServer, redisService, heartbeats);

        // Keep the application running until shutdown signal
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Main thread interrupted");
        }
    }

    private static void handleShutdown(SocketConfig config,
                                       SessionManager sessionManager,
                                       KafkaService kafkaService,
                                       HttpServer httpServer,
                                       RedisService redisService,
                                       Disposable heartbeats) {
        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received, initiating graceful shutdown...");
            MDC.put("nodeId", config.getNodeId());

            heartbeats.dispose();

            // Stop Kafka
            kafkaService.stop().block(Duration.ofSeconds(10));

            // Stop WS server
            httpServer.stop();

            // Drain connections
            sessionManager.drainAll().block(Duration.ofSeconds(30));

            // Close Redis
            redisService.close();

            log.info("Shutdown complete");
        }));
    }
}

