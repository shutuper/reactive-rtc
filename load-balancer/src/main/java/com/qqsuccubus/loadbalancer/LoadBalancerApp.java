package com.qqsuccubus.loadbalancer;

import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.http.HttpServer;
import com.qqsuccubus.loadbalancer.k8s.LeaderElectionService;
import com.qqsuccubus.loadbalancer.k8s.PodDeletionCostService;
import com.qqsuccubus.loadbalancer.k8s.PodInfoService;
import com.qqsuccubus.loadbalancer.k8s.SocketNodeScalerService;
import com.qqsuccubus.loadbalancer.kafka.KafkaService;
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
        log.info("  Leader Election: {}", config.isEnableLeaderElection());

        // Setup metrics
        PrometheusMetricsExporter metricsExporter = new PrometheusMetricsExporter(config.getNodeId());
        PrometheusQueryService prometheusQueryService = new PrometheusQueryService(
            config.getPrometheusHost(), config.getPrometheusPort()
        );
        NodeMetricsService nodeMetricsService = new NodeMetricsService(prometheusQueryService);

        // Initialize Kubernetes services (if leader election is enabled)
        LeaderElectionService leaderElectionService = null;
        PodDeletionCostService podDeletionCostService = null;
        SocketNodeScalerService socketNodeScalerService = null;
        PodInfoService podInfoService = null;

        if (config.isEnableLeaderElection()) {
            leaderElectionService = new LeaderElectionService(
                config.getKubernetesNamespace(),
                config.getNodeId()
            );
            podDeletionCostService = new PodDeletionCostService(config.getKubernetesNamespace());
            socketNodeScalerService = new SocketNodeScalerService(
                config.getKubernetesNamespace(),
                config.getSocketWorkloadName(),
                config.getSocketMinReplicas(),
                config.getSocketMaxReplicas()
            );
            podInfoService = new PodInfoService(config.getKubernetesNamespace());
            leaderElectionService.startLeaderElection();
            log.info("Leader election started for node {}", config.getNodeId());
        } else {
            // Still create PodInfoService for pod IP lookups even without leader election
            podInfoService = new PodInfoService(config.getKubernetesNamespace());
        }

        // Initialize components
        RedisService redisService = new RedisService(config);
        KafkaService kafkaService = new KafkaService(config);
        LoadBalancer loadBalancer = new LoadBalancer(
            config,
            kafkaService,
            redisService,
            metricsExporter.getRegistry(),
            nodeMetricsService,
            leaderElectionService,
            podDeletionCostService,
            socketNodeScalerService
        );

        // Start HTTP server
        HttpServer httpServer = new HttpServer(
            config,
            loadBalancer,
            metricsExporter,
            prometheusQueryService,
            nodeMetricsService,
            podInfoService
        );

        DisposableServer disposableServer = httpServer.start();

        Disposable heartbeats = redisService.subscribeToHeartbeats()
            .flatMap(loadBalancer::processHeartbeat)
            .subscribe();

        log.info("Load-Balancer is ready");

        handleShutDown(heartbeats, kafkaService, httpServer, redisService, leaderElectionService, podDeletionCostService, podInfoService, loadBalancer);

        disposableServer.onDispose().block();
    }

    private static void handleShutDown(
        Disposable heartbeats,
        KafkaService kafkaService,
        HttpServer httpServer,
        IRedisService redisService,
        LeaderElectionService leaderElectionService,
        PodDeletionCostService podDeletionCostService,
        PodInfoService podInfoService,
        LoadBalancer loadBalancer
    ) {
        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");

            heartbeats.dispose();

            // Stop load balancer (including message forwarders)
            loadBalancer.stop();

            kafkaService.close();

            httpServer.stop();

            redisService.close();

            if (leaderElectionService != null) {
                leaderElectionService.stop();
            }

            if (podDeletionCostService != null) {
                podDeletionCostService.close();
            }

            if (podInfoService != null) {
                podInfoService.close();
            }

            log.info("Shutdown complete");
        }));
    }
}
