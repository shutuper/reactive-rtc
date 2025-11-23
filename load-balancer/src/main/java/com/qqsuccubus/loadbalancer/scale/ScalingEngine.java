package com.qqsuccubus.loadbalancer.scale;

import com.qqsuccubus.core.metrics.MetricsNames;
import com.qqsuccubus.core.metrics.MetricsTags;
import com.qqsuccubus.core.model.Heartbeat;
import com.qqsuccubus.core.model.ScalingDirective;
import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.kafka.IRingPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Collection;

/**
 * Computes scaling directives based on domain metrics.
 * <p>
 * Formula:
 * <pre>
 *   r_conn = ceil(alpha * total_conn / conn_per_pod)
 *   r_mps  = ceil(beta  * total_mps  / mps_per_pod)
 *   r_lat  = ceil(gamma * exp(delta * max(0, p95/L_slo - 1)))
 *   target = max(r_conn, r_mps, r_lat)
 * </pre>
 * </p>
 * <p>
 * Includes hysteresis and max step per interval.
 * </p>
 */
public class ScalingEngine {
    private static final Logger log = LoggerFactory.getLogger(ScalingEngine.class);

    private final LBConfig config;
    private final IRingPublisher kafkaPublisher;

    private volatile int currentReplicas = 0;
    private volatile long lastScalingDecisionTs = 0;

    private final Counter scaleOutDecisions;
    private final Counter scaleInDecisions;
    private final Counter noScaleDecisions;

    public ScalingEngine(LBConfig config, IRingPublisher kafkaPublisher, MeterRegistry meterRegistry) {
        this.config = config;
        this.kafkaPublisher = kafkaPublisher;

        // Metrics
        scaleOutDecisions = Counter.builder(MetricsNames.LB_SCALING_DECISIONS_TOTAL)
                .tag(MetricsTags.ACTION, "scale_out")
                .register(meterRegistry);

        scaleInDecisions = Counter.builder(MetricsNames.LB_SCALING_DECISIONS_TOTAL)
                .tag(MetricsTags.ACTION, "scale_in")
                .register(meterRegistry);

        noScaleDecisions = Counter.builder(MetricsNames.LB_SCALING_DECISIONS_TOTAL)
                .tag(MetricsTags.ACTION, "none")
                .register(meterRegistry);
    }

    /**
     * Computes scaling directive from a collection of heartbeats.
     *
     * @param heartbeats Collection of node heartbeats
     * @return Mono of ScalingDirective
     */
    public Mono<ScalingDirective> computeScalingDirective(Collection<Heartbeat> heartbeats) {
        if (heartbeats.isEmpty()) {
            return Mono.just(ScalingDirective.builder()
                    .action(ScalingDirective.Action.NONE)
                    .reason("No active nodes")
                    .timestampMs(System.currentTimeMillis())
                    .build());
        }

        // Aggregate metrics
        int totalConn = heartbeats.stream().mapToInt(Heartbeat::getActiveConn).sum();
        double totalMps = heartbeats.stream().mapToDouble(Heartbeat::getMps).sum();
        double maxP95 = heartbeats.stream().mapToDouble(Heartbeat::getP95LatencyMs).max().orElse(0.0);

        // NEW: CPU and Memory metrics
        double avgCpu = heartbeats.stream().mapToDouble(Heartbeat::getCpu).average().orElse(0.0);
        double avgMem = heartbeats.stream().mapToDouble(Heartbeat::getMem).average().orElse(0.0);
        double maxCpu = heartbeats.stream().mapToDouble(Heartbeat::getCpu).max().orElse(0.0);
        double maxMem = heartbeats.stream().mapToDouble(Heartbeat::getMem).max().orElse(0.0);

        // Compute required replicas (connections, throughput, latency)
        int rConn = (int) Math.ceil(config.getAlpha() * totalConn / config.getConnPerPod());
        int rMps = (int) Math.ceil(config.getBeta() * totalMps / config.getMpsPerPod());
        double latencyRatio = Math.max(0, maxP95 / config.getLSloMs() - 1);
        int rLat = (int) Math.ceil(config.getGamma() * Math.exp(config.getDelta() * latencyRatio));

        // NEW: CPU-based scaling (scale if average CPU > 70% or any node > 85%)
        int rCpu = 0;
        if (avgCpu > 0.7 || maxCpu > 0.85) {
            rCpu = (int) Math.ceil(heartbeats.size() * Math.max(avgCpu, maxCpu) / 0.7);
        }

        // NEW: Memory-based scaling (scale if average memory > 75% or any node > 90%)
        int rMem = 0;
        if (avgMem > 0.75 || maxMem > 0.90) {
            rMem = (int) Math.ceil(heartbeats.size() * Math.max(avgMem, maxMem) / 0.75);
        }

        int targetReplicas = Math.max(Math.max(rConn, rMps), Math.max(rLat, Math.max(rCpu, rMem)));
        targetReplicas = Math.max(1, targetReplicas); // Minimum 1 replica

        log.info("Scaling computation: conn={}, mps={}, p95={}ms, cpu={}, mem={}, target={} (rConn={}, rMps={}, rLat={}, rCpu={}, rMem={})",
                totalConn, totalMps, maxP95, avgCpu, avgMem, targetReplicas, rConn, rMps, rLat, rCpu, rMem);

        // Throttle scaling decisions
        long now = System.currentTimeMillis();
        if (now - lastScalingDecisionTs < config.getScalingInterval().toMillis()) {
            return Mono.just(ScalingDirective.builder()
                    .action(ScalingDirective.Action.NONE)
                    .reason("Throttled (too soon)")
                    .timestampMs(now)
                    .build());
        }

        // Determine action
        int currentNodes = heartbeats.size();
        if (currentReplicas == 0) {
            currentReplicas = currentNodes; // Initialize on first call
        }

        ScalingDirective.Action action = ScalingDirective.Action.NONE;
        String reason = "Within SLO";

        if (targetReplicas > currentReplicas) {
            int step = Math.min(targetReplicas - currentReplicas, config.getMaxScaleStep());
            targetReplicas = currentReplicas + step;
            action = ScalingDirective.Action.SCALE_OUT;
            reason = String.format("Demand exceeds capacity (conn=%d, mps=%.0f, p95=%.1fms, cpu=%.1f%%, mem=%.1f%%)",
                    totalConn, totalMps, maxP95, avgCpu * 100, avgMem * 100);
            scaleOutDecisions.increment();
        } else if (targetReplicas < currentReplicas - 1) { // Hysteresis: at least 2 replicas below
            int step = Math.min(currentReplicas - targetReplicas, config.getMaxScaleStep());
            targetReplicas = currentReplicas - step;
            action = ScalingDirective.Action.SCALE_IN;

            // Detailed scale-in reason based on low utilization
            if (avgCpu < 0.3 && avgMem < 0.3 && totalConn < 100) {
                reason = String.format("Low utilization (conn=%d, cpu=%.1f%%, mem=%.1f%%)",
                        totalConn, avgCpu * 100, avgMem * 100);
            } else {
                reason = "Under-utilized";
            }
            scaleInDecisions.increment();
        } else {
            noScaleDecisions.increment();
        }

        if (action != ScalingDirective.Action.NONE) {
            lastScalingDecisionTs = now;
            currentReplicas = targetReplicas;
        }

        ScalingDirective directive = ScalingDirective.builder()
                .action(action)
                .targetReplicas(targetReplicas)
                .reason(reason)
                .timestampMs(now)
                .build();

        // Publish scale signal if action needed
        if (action != ScalingDirective.Action.NONE) {
            log.info("Scaling directive: action={}, target={}, reason={}",
                    action, targetReplicas, reason);

            ControlMessages.ScaleSignal scaleSignal = ControlMessages.ScaleSignal.builder()
                    .directive(directive)
                    .reason(reason)
                    .ts(now)
                    .build();

            return kafkaPublisher.publishScaleSignal(scaleSignal)
                    .thenReturn(directive);
        }

        return Mono.just(directive);
    }
}

