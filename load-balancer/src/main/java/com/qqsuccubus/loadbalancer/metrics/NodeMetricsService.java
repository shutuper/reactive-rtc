package com.qqsuccubus.loadbalancer.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for retrieving comprehensive per-node metrics from Prometheus.
 */
public class NodeMetricsService {
    private static final Logger log = LoggerFactory.getLogger(NodeMetricsService.class);
    private final PrometheusQueryService queryService;

    public NodeMetricsService(PrometheusQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Gets CPU usage per node (0.0 to 1.0).
     */
    private Mono<Map<String, Double>> getCpuByNode() {
        String query = "process_cpu_usage{job=\"socket-nodes\"}";
        return queryService.query(query)
                .map(r -> r.getValuesByLabel("instance"));
    }

    /**
     * Gets memory usage per node (0.0 to 1.0).
     */
    private Mono<Map<String, Double>> getMemoryByNode() {
        String query = "sum by (instance) (jvm_memory_used_bytes{job=\"socket-nodes\",area=\"heap\"}) / sum by (instance) (jvm_memory_max_bytes{job=\"socket-nodes\",area=\"heap\"})";
        return queryService.query(query)
                .map(r -> r.getValuesByLabel("instance"));
    }

    /**
     * Gets network metrics per node.
     * Returns a map with "inbound" and "outbound" sub-maps.
     */
    private Mono<Map<String, Map<String, Double>>> getNetworkByNode() {
        return Mono.zip(
                queryService.query("sum by (node_id) (rate(rtc_socket_network_inbound_ws_bytes_total{job=\"socket-nodes\"}[1m]) + rate(rtc_socket_network_inbound_kafka_bytes_total{job=\"socket-nodes\"}[1m]))")
                        .map(r -> r.getValuesByLabel("node_id")),
                queryService.query("sum by (node_id) (rate(rtc_socket_network_outbound_ws_bytes_total{job=\"socket-nodes\"}[1m]) + rate(rtc_socket_network_outbound_kafka_bytes_total{job=\"socket-nodes\"}[1m]))")
                        .map(r -> r.getValuesByLabel("node_id"))
        ).map(tuple -> {
            Map<String, Map<String, Double>> result = new HashMap<>();
            result.put("inbound", tuple.getT1());
            result.put("outbound", tuple.getT2());
            return result;
        });
    }

    /**
     * Gets Kafka delivery topic lag latency per node (milliseconds).
     * This is the last value (max), showing current lag from message timestamp to consumption.
     */
    private Mono<Map<String, Double>> getKafkaTopicLagLatencyByNode() {
        String query = "rtc_kafka_delivery_topic_lag_latency_seconds_max{job=\"socket-nodes\"} * 1000";
        return queryService.query(query)
                .map(r -> r.getValuesByLabel("node_id"))
                .doOnNext(values -> log.debug("Kafka topic lag latency by node: {}", values));
    }

    /**
     * Gets comprehensive metrics for all nodes.
     * Retrieves all metrics in parallel and builds NodeMetrics objects.
     *
     * @return Mono<Map<String, NodeMetrics>> map of nodeId -> NodeMetrics
     */
    public Mono<Map<String, NodeMetrics>> getAllNodeMetrics() {
        // Combine network metrics to reduce Mono.zip arguments (max 8)
        return Mono.zip(
                queryService.getActiveConnectionsByNode(),
                queryService.getMessageThroughputByNode(),
                queryService.getP95LatencyByNode(),
                getCpuByNode(),
                getMemoryByNode(),
                queryService.getKafkaConsumerLagByNode(),
                getNetworkByNode(),
                getKafkaTopicLagLatencyByNode()
        ).map(tuple -> {
            Map<String, Double> connections = tuple.getT1();
            Map<String, Double> throughput = tuple.getT2();
            Map<String, Double> latency = tuple.getT3();
            Map<String, Double> cpu = tuple.getT4();
            Map<String, Double> memory = tuple.getT5();
            Map<String, Double> kafkaLag = tuple.getT6();
            Map<String, Map<String, Double>> network = tuple.getT7();
            Map<String, Double> kafkaTopicLagLatency = tuple.getT8();

            Map<String, Double> networkIn = network.get("inbound");
            Map<String, Double> networkOut = network.get("outbound");

            Map<String, NodeMetrics> result = new HashMap<>();

            for (String nodeId : connections.keySet()) {
                String consumerGroup = "socket-delivery-" + nodeId;
                String instanceHint = nodeId.replace("socket-node-", "socket-");

                Double cpuVal = cpu.entrySet().stream()
                        .filter(e -> e.getKey().contains(instanceHint))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse(0.0);

                Double memVal = memory.entrySet().stream()
                        .filter(e -> e.getKey().contains(instanceHint))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse(0.0);

                NodeMetrics metrics = NodeMetrics.builder()
                        .nodeId(nodeId)
                        .activeConn(connections.getOrDefault(nodeId, 0.0).intValue())
                        .mps(throughput.getOrDefault(nodeId, 0.0))
                        .p95LatencyMs(latency.getOrDefault(nodeId, 0.0) * 1000)
                        .kafkaTopicLagLatencyMs(kafkaTopicLagLatency.getOrDefault(nodeId, 0.0))
                        .cpu(cpuVal)
                        .mem(memVal)
                        .kafkaConsumerLag(kafkaLag.getOrDefault(consumerGroup, 0.0).intValue())
                        .networkInboundBytesPerSec(networkIn.getOrDefault(nodeId, 0.0))
                        .networkOutboundBytesPerSec(networkOut.getOrDefault(nodeId, 0.0))
                        .healthy(true)
                        .build();

                result.put(nodeId, metrics);
                log.debug("Built metrics for node {}: {}", nodeId, metrics);
            }

            log.info("Retrieved comprehensive metrics for {} nodes", result.size());
            return result;
        });
    }
}
