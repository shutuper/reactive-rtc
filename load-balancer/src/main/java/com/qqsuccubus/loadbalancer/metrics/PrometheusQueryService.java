package com.qqsuccubus.loadbalancer.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qqsuccubus.core.util.JsonUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Service for querying Prometheus metrics using reactor-netty HttpClient.
 * Provides methods to fetch aggregated metrics across nodes for autoscaling decisions.
 * <p>
 * Configuration:
 * - Local: Prometheus at http://prometheus:9090
 * - Kubernetes: Prometheus service at http://prometheus-service.monitoring.svc.cluster.local:9090
 * </p>
 */
public class PrometheusQueryService {
    private static final Logger log = LoggerFactory.getLogger(PrometheusQueryService.class);

    private final HttpClient httpClient;

    /**
     * Creates a Prometheus query service.
     *
     * @param prometheusHost Prometheus host (e.g., "prometheus" or "prometheus-service.monitoring.svc.cluster.local")
     * @param prometheusPort Prometheus port (typically 9090)
     */
    public PrometheusQueryService(String prometheusHost, int prometheusPort) {
        this.httpClient = HttpClient.create()
                .host(prometheusHost)
                .port(prometheusPort)
                .headers(h -> h.set(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON))
                .responseTimeout(Duration.ofSeconds(30));

        log.info("PrometheusQueryService initialized with {}:{}", prometheusHost, prometheusPort);
    }

    /**
     * Executes a PromQL query and returns the result.
     *
     * @param query PromQL query string
     * @return Mono<PrometheusQueryResult> containing query results
     */
    public Mono<PrometheusQueryResult> query(String query) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        String uri = "/api/v1/query?query=" + encodedQuery;

        log.debug("Executing Prometheus query: {}", query);

        return httpClient.get()
                .uri(uri)
                .responseContent()
                .aggregate()
                .asString()
                .retry(5)
                .flatMap(responseBody -> {
                    try {
                        PrometheusResponse response = JsonUtils.readValue(responseBody, PrometheusResponse.class);

                        if (!"success".equalsIgnoreCase(response.getStatus())) {
                            log.error("Prometheus query failed: {}", response.getError());
                            return Mono.just(PrometheusQueryResult.empty());
                        }

                        log.debug("Prometheus query successful, result count: {}",
                                response.getData() != null && response.getData().getResult() != null
                                        ? response.getData().getResult().size() : 0);

                        return Mono.just(PrometheusQueryResult.from(response));
                    } catch (Exception e) {
                        log.error("Failed to parse Prometheus response: {}", e.getMessage(), e);
                        return Mono.just(PrometheusQueryResult.empty());
                    }
                })
                .doOnError(err -> log.error("Failed to query Prometheus: {}", err.getMessage()))
                .onErrorReturn(PrometheusQueryResult.empty());
    }

    /**
     * Gets total message throughput across all socket nodes (messages/sec).
     *
     * @return Mono<Double> total messages per second
     */
    public Mono<Double> getTotalMessageThroughput() {
        String query = "sum(rate(rtc_socket_deliver_mps_total[1m]))";
        return query(query)
                .map(result -> result.getValue().orElse(0.0))
                .doOnNext(value -> log.debug("Total throughput: {} msg/s", value));
    }

    /**
     * Gets message throughput per node (messages/sec).
     *
     * @return Mono<Map<String, Double>> map of nodeId -> messages/sec
     */
    public Mono<Map<String, Double>> getMessageThroughputByNode() {
        String query = "sum by (node_id) (rate(rtc_socket_deliver_mps_total[1m]))";
        return query(query)
                .map(result -> result.getValuesByLabel("node_id"))
                .doOnNext(values -> log.debug("Throughput by node: {}", values));
    }

    /**
     * Gets p95 latency across all socket nodes (seconds).
     *
     * @return Mono<Double> p95 latency in seconds
     */
    public Mono<Double> getP95Latency() {
        String query = "histogram_quantile(0.95, sum(rate(rtc_socket_latency_seconds_bucket[5m])) by (le))";
        return query(query)
                .map(result -> result.getValue().orElse(0.0))
                .doOnNext(value -> log.debug("p95 latency: {}s ({}ms)", value, value * 1000));
    }

    /**
     * Gets p95 latency per node (seconds).
     *
     * @return Mono<Map<String, Double>> map of nodeId -> p95 latency in seconds
     */
    public Mono<Map<String, Double>> getP95LatencyByNode() {
        String query = "histogram_quantile(0.95, sum by (node_id, le) (rate(rtc_socket_latency_seconds_bucket[5m])))";
        return query(query)
                .map(result -> result.getValuesByLabel("node_id"))
                .doOnNext(values -> log.debug("p95 latency by node: {}", values));
    }

    /**
     * Gets total active connections across all socket nodes.
     *
     * @return Mono<Double> total active connections
     */
    public Mono<Double> getTotalActiveConnections() {
        String query = "sum(rtc_socket_active_connections)";
        return query(query)
                .map(result -> result.getValue().orElse(0.0))
                .doOnNext(value -> log.debug("Total connections: {}", value));
    }

    /**
     * Gets active connections per node.
     *
     * @return Mono<Map<String, Double>> map of nodeId -> connection count
     */
    public Mono<Map<String, Double>> getActiveConnectionsByNode() {
        String query = "rtc_socket_active_connections";
        return query(query)
                .map(result -> result.getValuesByLabel("node_id"))
                .doOnNext(values -> log.debug("Connections by node: {}", values));
    }

    /**
     * Gets total Kafka consumer lag across all socket nodes.
     *
     * @return Mono<Double> total consumer lag
     */
    public Mono<Double> getTotalKafkaConsumerLag() {
        String query = "sum(kafka_consumergroup_lag_sum{consumergroup=~\"socket-delivery-.*\"})";
        return query(query)
                .map(result -> result.getValue().orElse(0.0))
                .doOnNext(value -> log.debug("Total Kafka lag: {} messages", value));
    }

    /**
     * Gets Kafka consumer lag per node.
     *
     * @return Mono<Map<String, Double>> map of consumergroup -> lag
     */
    public Mono<Map<String, Double>> getKafkaConsumerLagByNode() {
        String query = "kafka_consumergroup_lag_sum{consumergroup=~\"socket-delivery-.*\"}";
        return query(query)
                .map(result -> result.getValuesByLabel("consumergroup"))
                .doOnNext(values -> log.debug("Kafka lag by consumer group: {}", values));
    }

    /**
     * Gets count of socket nodes currently up.
     *
     * @return Mono<Double> number of socket nodes
     */
    public Mono<Double> getSocketNodeCount() {
        String query = "count(up{job=\"socket-nodes\"} == 1)";
        return query(query)
                .map(result -> result.getValue().orElse(0.0))
                .doOnNext(value -> log.debug("Socket node count: {}", value));
    }

    /**
     * Gets comprehensive metrics for autoscaling decision.
     *
     * @return Mono<MetricsSummary> aggregated metrics
     */
    public Mono<MetricsSummary> getMetricsSummary() {
        return Mono.zip(
                getTotalMessageThroughput(),
                getP95Latency(),
                getTotalActiveConnections(),
                getTotalKafkaConsumerLag(),
                getSocketNodeCount()
        ).map(tuple -> {
            MetricsSummary summary = MetricsSummary.builder()
                    .totalThroughput(tuple.getT1())
                    .p95LatencySeconds(tuple.getT2())
                    .totalConnections(tuple.getT3().intValue())
                    .totalConsumerLag(tuple.getT4().intValue())
                    .nodeCount(tuple.getT5().intValue())
                    .build();

            log.info("Metrics summary: throughput={}msg/s, p95={}ms, connections={}, lag={}, nodes={}",
                    summary.getTotalThroughput(),
                    summary.getP95LatencySeconds(),
                    summary.getTotalConnections(),
                    summary.getTotalConsumerLag(),
                    summary.getNodeCount());

            return summary;
        });
    }

    /**
     * Health check - tests Prometheus connectivity.
     *
     * @return Mono<Boolean> true if Prometheus is reachable
     */
    public Mono<Boolean> healthCheck() {
        return httpClient.get()
                .uri("/-/healthy")
                .responseSingle((response, body) -> Mono.just(response.status().code() == 200))
                .timeout(Duration.ofSeconds(3))
                .doOnNext(healthy -> {
                    if (healthy) {
                        log.debug("Prometheus health check: OK");
                    } else {
                        log.warn("Prometheus health check: FAILED");
                    }
                })
                .onErrorReturn(false);
    }

    /**
     * Data class for Prometheus API response.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrometheusResponse {
        private String status;
        private PrometheusData data;
        private String error;
        private String errorType;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrometheusData {
        private String resultType;
        private List<PrometheusResult> result;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrometheusResult {
        private Map<String, String> metric;
        private List<Object> value;
    }
}



