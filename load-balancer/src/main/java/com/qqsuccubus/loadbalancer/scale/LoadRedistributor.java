package com.qqsuccubus.loadbalancer.scale;

import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.kafka.IRingPublisher;
import com.qqsuccubus.loadbalancer.ring.ILoadBalancer;
import com.qqsuccubus.loadbalancer.ring.LoadBalancer.NodeEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Periodically redistributes load by requesting graceful disconnections.
 * <p>
 * When ring topology changes or nodes are unevenly loaded, this service
 * triggers graceful disconnections to redistribute users across nodes.
 * </p>
 */
public class LoadRedistributor {
    private static final Logger log = LoggerFactory.getLogger(LoadRedistributor.class);

    private final ILoadBalancer ringManager;
    private final IRingPublisher kafkaPublisher;
    private final LBConfig config;

    public LoadRedistributor(ILoadBalancer ringManager, IRingPublisher kafkaPublisher, LBConfig config) {
        this.ringManager = ringManager;
        this.kafkaPublisher = kafkaPublisher;
        this.config = config;
    }

    /**
     * Starts periodic load redistribution checks.
     *
     * @return Flux that emits on each check interval
     */
    public Flux<Void> start() {
        return Flux.interval(Duration.ofMinutes(5))
                .flatMap(tick -> checkAndRedistribute())
                .doOnError(err -> log.error("Load redistribution error", err))
                .onErrorContinue((err, obj) -> log.warn("Continuing redistribution despite error"));
    }

    /**
     * Checks load distribution and triggers redistribution if needed.
     */
    private Mono<Void> checkAndRedistribute() {
        return Mono.fromCallable(() -> {
            Map<String, NodeEntry> nodes = ringManager.getActiveNodes();

            if (nodes.size() < 2) {
                return null; // Need at least 2 nodes for redistribution
            }

            // Calculate load variance
            List<Integer> loads = new ArrayList<>();
            for (NodeEntry entry : nodes.values()) {
                Integer conn = entry.lastHeartbeat.getActiveConn();
                loads.add(conn);
            }

            // Calculate statistics
            double avgLoad = loads.stream().mapToInt(i -> i).average().orElse(0.0);
            int maxLoad = loads.stream().mapToInt(i -> i).max().orElse(0);
            int minLoad = loads.stream().mapToInt(i -> i).min().orElse(0);

            double variance = calculateVariance(loads, avgLoad);
            double stdDev = Math.sqrt(variance);
            double coefficientOfVariation = stdDev / (avgLoad > 0 ? avgLoad : 1.0);

            log.info("Load distribution: avg={}, max={}, min={}, cv={}",
                    avgLoad, maxLoad, minLoad, coefficientOfVariation);

            // Trigger redistribution if load is very uneven (CV > 0.5)
            if (coefficientOfVariation > 0.5 && avgLoad > 0) {
                log.info("Uneven load detected (CV={}), triggering graceful disconnections",
                        coefficientOfVariation);

                triggerRedistribution(nodes, maxLoad);
            }

            // Trigger redistribution if a node has > 150% of average load
            if (maxLoad > 1.5 * avgLoad && avgLoad > 10) {
                log.info("Node overload detected (max={}, avg={}), triggering redistribution",
                        maxLoad, avgLoad);

                triggerRedistribution(nodes, maxLoad);
            }

            return null;
        }).then();
    }

    /**
     * Triggers graceful disconnections for overloaded nodes.
     */
    private void triggerRedistribution(Map<String, NodeEntry> nodes, int maxLoad) {
        for (Map.Entry<String, NodeEntry> entry : nodes.entrySet()) {
            NodeEntry nodeEntry = entry.getValue();
            int nodeLoad = Optional.of(nodeEntry.lastHeartbeat().getActiveConn()).orElse(0);

            // If this node is overloaded (more than 20% above average)
            if (nodeLoad > maxLoad * 0.8) {
                // Calculate how many connections to disconnect (20% of overloaded part)
                int excessLoad = nodeLoad - (int)(maxLoad * 0.5);
                int disconnectCount = excessLoad / 5; // Disconnect 20% of excess

                if (disconnectCount > 0) {
                    log.info("Requesting {} graceful disconnections from {}",
                            disconnectCount, entry.getKey());

                    ControlMessages.DrainSignal drainSignal = ControlMessages.DrainSignal.builder()
                            .nodeId(entry.getKey())
                            .maxDisconnects(disconnectCount)
                            .reason("Load redistribution")
                            .ts(System.currentTimeMillis())
                            .build();

                    kafkaPublisher.publishDrainSignal(drainSignal).subscribe(
                            v -> {},
                            err -> log.error("Failed to publish drain signal for {}", entry.getKey(), err)
                    );
                }
            }
        }
    }

    /**
     * Calculates variance of a list of values.
     */
    private double calculateVariance(List<Integer> values, double mean) {
        return values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);
    }
}

