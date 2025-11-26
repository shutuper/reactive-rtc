package com.qqsuccubus.loadbalancer.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.metrics.NodeMetricsService;
import com.qqsuccubus.loadbalancer.metrics.PrometheusMetricsExporter;
import com.qqsuccubus.loadbalancer.metrics.PrometheusQueryService;
import com.qqsuccubus.loadbalancer.ring.ILoadBalancer;
import com.qqsuccubus.loadbalancer.ring.LoadBalancer;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServerRoutes;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP server for load-balancer endpoints.
 */
public class HttpServer {
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final LBConfig config;
    private final ILoadBalancer ringManager;
    private final PrometheusMetricsExporter metricsExporter;
    private final PrometheusQueryService prometheusQueryService;
    @Getter
    private final NodeMetricsService nodeMetricsService;

    private DisposableServer server;

    public HttpServer(
        LBConfig config,
        ILoadBalancer ringManager,
        PrometheusMetricsExporter metricsExporter,
        PrometheusQueryService prometheusQueryService,
        NodeMetricsService nodeMetricsService
    ) {
        this.config = config;
        this.ringManager = ringManager;
        this.metricsExporter = metricsExporter;
        this.prometheusQueryService = prometheusQueryService;
        this.nodeMetricsService = nodeMetricsService;
    }

    /**
     * Starts the HTTP server.
     *
     * @return Mono<Integer> of the bound port
     */
    public Mono<Integer> start() {
        server = reactor.netty.http.server.HttpServer.create()
            .port(config.getHttpPort())
            .route(this::configureRoutes)
            .bindNow();

        return Mono.just(server.port());
    }

    public void stop() {
        server.disposeNow(Duration.ofSeconds(20));
    }

    private void configureRoutes(HttpServerRoutes routes) {
        routes
            // Health check
            .get("/healthz", (req, res) ->
                res.status(200).sendString(Mono.just("OK"))
            )
            // Metrics endpoint
            .get("/metrics", (req, res) ->
                res.addHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .sendString(Mono.just(metricsExporter.scrape()))
                    .then()
            )
            // Resolve node for userId
            .get("/api/v1/resolve", (req, res) -> {
                String userId = req.param("userId");
                if (userId == null || userId.isEmpty()) {
                    return res.status(HttpResponseStatus.BAD_REQUEST)
                        .sendString(Mono.just("{\"error\":\"Missing userId parameter\"}"));
                }

                LoadBalancer.NodeEntry node = ringManager.resolveNode(userId);
                if (node == null) {
                    return res.status(HttpResponseStatus.SERVICE_UNAVAILABLE)
                        .sendString(Mono.just("{\"error\":\"No nodes available\"}"));
                }

                return Mono.fromCallable(() -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("nodeId", node.nodeId());
                    return MAPPER.writeValueAsString(response);
                }).flatMap(json ->
                    res.header("Content-Type", "application/json")
                        .sendString(Mono.just(json)).then()
                ).onErrorResume(err ->
                    res.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                        .sendString(Mono.just("{\"error\":\"Serialization failed\"}")).then()
                ).then(res.send());
            })
            // Connect endpoint
            .get("/api/v1/connect", (req, res) -> {
                String userId = req.param("userId");
                if (userId == null || userId.isEmpty()) {
                    return res.status(HttpResponseStatus.BAD_REQUEST)
                        .sendString(Mono.just("{\"error\":\"Missing userId parameter\"}"));
                }

                LoadBalancer.NodeEntry node = ringManager.resolveNode(userId);
                if (node == null) {
                    return res.status(HttpResponseStatus.SERVICE_UNAVAILABLE)
                        .sendString(Mono.just("{\"error\":\"No nodes available\"}"));
                }

                return Mono.fromCallable(() -> {
                    Map<String, Object> response = new HashMap<>();
                    return MAPPER.writeValueAsString(response);
                }).flatMap(json ->
                    res.header("Content-Type", "application/json")
                        .sendString(Mono.just(json)).then()
                ).onErrorResume(err ->
                    res.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                        .sendString(Mono.just("{\"error\":\"Serialization failed\"}")).then()
                ).then(res.send());
            })
            // Prometheus Query: Metrics Summary
            .get("/api/v1/query/metrics-summary", (req, res) ->
                prometheusQueryService.getMetricsSummary()
                    .flatMap(summary -> Mono.fromCallable(() ->
                        MAPPER.writeValueAsString(summary)))
                    .flatMap(json ->
                        res.header("Content-Type", "application/json")
                            .sendString(Mono.just(json)).then()
                    )
                    .onErrorResume(err -> {
                        log.error("Failed to get metrics summary", err);
                        return res.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                            .sendString(Mono.just("{\"error\":\"Failed to query metrics\"}")).then();
                    })
                    .then(res.send())
            )
            // Prometheus Query: Custom PromQL
            .get("/api/v1/query/promql", (req, res) -> {
                String query = req.param("query");
                if (query == null || query.isEmpty()) {
                    return res.status(HttpResponseStatus.BAD_REQUEST)
                        .sendString(Mono.just("{\"error\":\"Missing query parameter\"}"));
                }

                return prometheusQueryService.query(query)
                    .flatMap(result -> Mono.fromCallable(() -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("value", result.getValue().orElse(null));
                        response.put("resultCount", result.size());
                        response.put("results", result.getResults());
                        return MAPPER.writeValueAsString(response);
                    }))
                    .flatMap(json ->
                        res.header("Content-Type", "application/json")
                            .sendString(Mono.just(json)).then()
                    )
                    .onErrorResume(err -> {
                        log.error("Failed to execute Prometheus query", err);
                        return res.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                            .sendString(Mono.just("{\"error\":\"Query failed\"}")).then();
                    })
                    .then(res.send());
            })
            // Prometheus Query: Throughput by Node
            .get("/api/v1/query/throughput-by-node", (req, res) ->
                prometheusQueryService.getMessageThroughputByNode()
                    .flatMap(throughput -> Mono.fromCallable(() ->
                        MAPPER.writeValueAsString(throughput)))
                    .flatMap(json ->
                        res.header("Content-Type", "application/json")
                            .sendString(Mono.just(json)).then()
                    )
                    .onErrorResume(err ->
                        res.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                            .sendString(Mono.just("{\"error\":\"Query failed\"}")).then()
                    )
                    .then(res.send())
            )
            // Prometheus Query: Kafka Consumer Lag by Node
            .get("/api/v1/query/kafka-lag-by-node", (req, res) ->
                prometheusQueryService.getKafkaConsumerLagByNode()
                    .flatMap(lag -> Mono.fromCallable(() ->
                        MAPPER.writeValueAsString(lag)))
                    .flatMap(json ->
                        res.header("Content-Type", "application/json")
                            .sendString(Mono.just(json)).then()
                    )
                    .onErrorResume(err ->
                        res.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                            .sendString(Mono.just("{\"error\":\"Query failed\"}")).then()
                    )
                    .then(res.send())
            )// Prometheus Query: All Node Metrics
            .get("/api/v1/query/node-metrics", (req, res) ->
                nodeMetricsService.getAllNodeMetrics()
                    .flatMap(nodeMetrics -> Mono.fromCallable(() ->
                        MAPPER.writeValueAsString(nodeMetrics)))
                    .flatMap(json ->
                        res.header("Content-Type", "application/json")
                            .sendString(Mono.just(json)).then()
                    )
                    .onErrorResume(err -> {
                        log.error("Failed to get node metrics", err);
                        return res.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                            .sendString(Mono.just("{\"error\":\"Failed to query metrics\"}")).then();
                    })
                    .then(res.send())
            );
    }

}
