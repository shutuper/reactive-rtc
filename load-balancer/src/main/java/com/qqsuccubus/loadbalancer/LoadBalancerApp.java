package com.qqsuccubus.loadbalancer;

import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.http.HttpServer;
import com.qqsuccubus.loadbalancer.kafka.KafkaPublisher;
import com.qqsuccubus.loadbalancer.metrics.NodeMetricsService;
import com.qqsuccubus.loadbalancer.metrics.PrometheusMetricsExporter;
import com.qqsuccubus.loadbalancer.metrics.PrometheusQueryService;
import com.qqsuccubus.loadbalancer.redis.IRedisService;
import com.qqsuccubus.loadbalancer.redis.RedisService;
import com.qqsuccubus.loadbalancer.ring.LoadBalancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.netty.DisposableServer;

public class LoadBalancerApp {
    private static final Logger log = LoggerFactory.getLogger(LoadBalancerApp.class);

    public static void main(String[] args) {
        // Set virtual threads property BEFORE any config loading to ensure it's set early
        System.setProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true");

        LBConfig config = LBConfig.fromEnv();

        log.info("Starting Load-Balancer");
        log.info("  Kafka: {}", config.getKafkaBootstrap());

        // Setup metrics
        PrometheusMetricsExporter metricsExporter = new PrometheusMetricsExporter(config.getNodeId());
        PrometheusQueryService prometheusQueryService = new PrometheusQueryService(
            config.getPrometheusHost(), config.getPrometheusPort()
        );
        NodeMetricsService nodeMetricsService = new NodeMetricsService(prometheusQueryService);

        // Initialize components
        RedisService redisService = new RedisService(config);
        KafkaPublisher kafkaPublisher = new KafkaPublisher(config);
        LoadBalancer loadBalancer = new LoadBalancer(config, kafkaPublisher, redisService, metricsExporter.getRegistry(), nodeMetricsService);

        // Start HTTP server
        HttpServer httpServer = new HttpServer(
            config,
            loadBalancer,
            metricsExporter,
            prometheusQueryService,
            nodeMetricsService
        );

        DisposableServer disposableServer = httpServer.start();

        Disposable heartbeats = redisService.subscribeToHeartbeats()
            .flatMap(loadBalancer::processHeartbeat)
            .subscribe();

        log.info("Load-Balancer is ready");

        handleShutDown(heartbeats, kafkaPublisher, httpServer, redisService);

        disposableServer.onDispose().block();
    }

    private static void handleShutDown(Disposable heartbeats, KafkaPublisher kafkaPublisher, HttpServer httpServer, IRedisService redisService) {
        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");

            heartbeats.dispose();

            kafkaPublisher.close();

            httpServer.stop();

            redisService.close();

            log.info("Shutdown complete");
        }));
    }
}
