package com.qqsuccubus.loadbalancer.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qqsuccubus.core.model.Heartbeat;
import com.qqsuccubus.core.model.NodeDescriptor;
import com.qqsuccubus.core.model.RingSnapshot;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.metrics.PrometheusMetricsExporter;
import com.qqsuccubus.loadbalancer.metrics.PrometheusQueryService;
import com.qqsuccubus.loadbalancer.ring.IRingManager;
import com.qqsuccubus.loadbalancer.scale.ScalingEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServerRoutes;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HTTP server for load-balancer endpoints.
 */
public class HttpServer {
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final LBConfig config;
    private final IRingManager ringManager;
    private final ScalingEngine scalingEngine;
    private final PrometheusMetricsExporter metricsExporter;
    private final PrometheusQueryService prometheusQueryService;

    public HttpServer(
            LBConfig config,
            IRingManager ringManager,
            ScalingEngine scalingEngine,
            MeterRegistry meterRegistry
    ) {
        this.config = config;
        this.ringManager = ringManager;
        this.scalingEngine = scalingEngine;
        this.metricsExporter = new PrometheusMetricsExporter(meterRegistry);
        this.prometheusQueryService = new PrometheusQueryService(
                config.getPrometheusHost(),
                config.getPrometheusPort()
        );
    }

    /**
     * Starts the HTTP server.
     *
     * @return Mono<Integer> of the bound port
     */
    public Mono<Integer> start() {
        DisposableServer server = reactor.netty.http.server.HttpServer.create()
                .port(config.getHttpPort())
                .route(this::configureRoutes)
                .bindNow();

        return Mono.just(server.port());
    }

    private void configureRoutes(HttpServerRoutes routes) {
        routes
                // Health check
                .get("/healthz", (req, res) ->
                        res.sendString(Mono.just("OK"))
                )
                // Metrics endpoint
                .get("/metrics", (req, res) ->
                        res.addHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                                .sendString(Mono.just(metricsExporter.scrape()))
                                .then()
                )
                // Heartbeat endpoint
                .post("/api/v1/nodes/heartbeat", (req, res) ->
                        req.receive().aggregate().asString()
                                .flatMap(this::processHeartbeat)
                                .flatMap(responseJson ->
                                        res.status(HttpResponseStatus.OK)
                                                .header("Content-Type", "application/json")
                                                .sendString(Mono.just(responseJson)).then()
                                )
                                .onErrorResume(err -> {
                                    log.error("Error processing heartbeat", err);
                                    return res.status(HttpResponseStatus.BAD_REQUEST)
                                            .sendString(Mono.just("{\"error\":\"Invalid heartbeat\"}")).then();
                                })
                                .then(res.status(200).send())
                )
                // Get ring snapshot
                .get("/api/v1/ring", (req, res) ->
                        Mono.fromCallable(() -> {
                            RingSnapshot snapshot = ringManager.getRingSnapshot();
                            return MAPPER.writeValueAsString(snapshot);
                        }).flatMap(json ->
                                res.header("Content-Type", "application/json")
                                        .sendString(Mono.just(json)).then()
                        ).onErrorResume(err ->
                                res.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                                        .sendString(Mono.just("{\"error\":\"Failed to serialize ring\"}")).then()
                        ).then(res.send())
                )
                // Resolve node for userId
                .get("/api/v1/resolve", (req, res) -> {
                    String userId = req.param("userId");
                    if (userId == null || userId.isEmpty()) {
                        return res.status(HttpResponseStatus.BAD_REQUEST)
                                .sendString(Mono.just("{\"error\":\"Missing userId parameter\"}"));
                    }

                    NodeDescriptor node = ringManager.resolveNode(userId);
                    if (node == null) {
                        return res.status(HttpResponseStatus.SERVICE_UNAVAILABLE)
                                .sendString(Mono.just("{\"error\":\"No nodes available\"}"));
                    }

                    return Mono.fromCallable(() -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("nodeId", node.getNodeId());
                        response.put("publicWsUrl", node.getPublicWsUrl());
                        response.put("version", ringManager.getRingSnapshot().getVersion().getVersion());
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

                    NodeDescriptor node = ringManager.resolveNode(userId);
                    if (node == null) {
                        return res.status(HttpResponseStatus.SERVICE_UNAVAILABLE)
                                .sendString(Mono.just("{\"error\":\"No nodes available\"}"));
                    }

                    return Mono.fromCallable(() -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("wsUrl", node.getPublicWsUrl() + "?userId=" + userId);
                        response.put("ringVersion", ringManager.getRingSnapshot().getVersion().getVersion());
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
                );
    }

    private Mono<String> processHeartbeat(String body) {
        return Mono.fromCallable(() -> MAPPER.readValue(body, Heartbeat.class))
                .flatMap(heartbeat ->
                    ringManager.processHeartbeat(heartbeat)
                            .then(scalingEngine.computeScalingDirective(
                                    ringManager.getActiveNodes().values().stream()
                                            .map(e -> e.lastHeartbeat)
                                            .collect(Collectors.toList())
                            ))
                        .handle((directive, sink) -> {
                            Map<String, Object> response = new HashMap<>();
                            response.put("accepted", true);
                            response.put("ringVersion", ringManager.getRingSnapshot().getVersion().getVersion());
                            response.put("maybeScale", directive.getAction().name());
                            try {
                                sink.next(MAPPER.writeValueAsString(response));
                            } catch (JsonProcessingException e) {
                                sink.error(new RuntimeException(e));
                            }
                        })
                );
    }
}
