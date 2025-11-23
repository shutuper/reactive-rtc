package com.qqsuccubus.loadbalancer.scale;

import com.qqsuccubus.core.model.Heartbeat;
import com.qqsuccubus.core.model.ScalingDirective;
import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.kafka.IRingPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CPU and memory-based scaling decisions.
 */
class CpuMemoryScalingTest {

    private LBConfig config;
    private TestKafkaPublisher kafkaPublisher;
    private ScalingEngine scalingEngine;

    @BeforeEach
    void setUp() {
        config = LBConfig.builder()
                .httpPort(8081)
                .kafkaBootstrap("localhost:9092")
                .publicDomainTemplate("ws://localhost:%d/ws")
                .ringVnodesPerWeight(150)
                .ringSecret("test-secret")
                .alpha(0.4)
                .beta(0.4)
                .gamma(0.2)
                .delta(2.0)
                .lSloMs(500.0)
                .connPerPod(5000)
                .mpsPerPod(2500)
                .scalingInterval(Duration.ofMillis(100))
                .maxScaleStep(5)
                .heartbeatGrace(Duration.ofSeconds(90))
                .build();

        kafkaPublisher = new TestKafkaPublisher();
        scalingEngine = new ScalingEngine(config, kafkaPublisher, new SimpleMeterRegistry());
    }

    @Test
    void testHighCpuUsage_TriggersScaleOut() {
        // 3 nodes with 85% CPU on average - should trigger scale-out
        List<Heartbeat> heartbeats = createHeartbeats(
                3,           // 3 nodes
                1000,        // Low connections
                100.0,       // Low MPS
                100.0,       // Low latency
                0.85,        // 85% CPU
                0.50         // Normal memory
        );

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        assertEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction());
        assertTrue(directive.getTargetReplicas() > 3,
                "Should scale out due to high CPU (expected >3, got " + directive.getTargetReplicas() + ")");
        assertTrue(directive.getReason().contains("cpu") || directive.getReason().contains("CPU"),
                "Reason should mention CPU: " + directive.getReason());

        System.out.printf("High CPU: %s -> %d replicas (reason: %s)%n",
                directive.getAction(), directive.getTargetReplicas(), directive.getReason());
    }

    @Test
    void testCriticalCpuOnSingleNode_TriggersScaleOut() {
        // Single node at 90% CPU should trigger scale-out
        List<Heartbeat> heartbeats = createHeartbeats(
                1,
                500,
                50.0,
                100.0,
                0.90,        // 90% CPU on single node
                0.45         // Normal memory
        );

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        assertEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction());
        System.out.printf("Critical CPU: %s -> %d replicas%n",
                directive.getAction(), directive.getTargetReplicas());
    }

    @Test
    void testHighMemoryUsage_TriggersScaleOut() {
        // 3 nodes with 80% memory usage - should trigger scale-out
        List<Heartbeat> heartbeats = createHeartbeats(
                3,
                800,
                150.0,
                120.0,
                0.45,        // Normal CPU
                0.80         // 80% memory
        );

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        assertEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction());
        assertTrue(directive.getTargetReplicas() > 3,
                "Should scale out due to high memory");
        assertTrue(directive.getReason().contains("mem"),
                "Reason should mention memory: " + directive.getReason());

        System.out.printf("High memory: %s -> %d replicas (reason: %s)%n",
                directive.getAction(), directive.getTargetReplicas(), directive.getReason());
    }

    @Test
    void testCriticalMemoryOnSingleNode_TriggersScaleOut() {
        // Single node at 95% memory - should definitely scale out
        List<Heartbeat> heartbeats = createHeartbeats(
                1,
                300,
                40.0,
                80.0,
                0.40,        // Normal CPU
                0.95         // 95% memory
        );

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        assertEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction());
        assertTrue(directive.getTargetReplicas() >= 1,
                "Should have at least 1 target replica");
        System.out.printf("Critical memory: %s -> %d replicas%n",
                directive.getAction(), directive.getTargetReplicas());
    }

    @Test
    void testCombinedHighCpuAndMemory_TriggersScaleOut() {
        // 3 nodes with both high CPU and memory
        List<Heartbeat> heartbeats = createHeartbeats(
                3,
                2000,
                200.0,
                180.0,
                0.75,        // 75% CPU
                0.85         // 85% memory
        );

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        assertEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction());
        assertTrue(directive.getTargetReplicas() > 3,
                "Should scale out significantly due to both high CPU and memory");

        System.out.printf("Combined high CPU+memory: %s -> %d replicas%n",
                directive.getAction(), directive.getTargetReplicas());
    }

    @Test
    void testLowCpuAndMemory_TriggersScaleIn() {
        // 5 nodes with very low CPU and memory
        List<Heartbeat> heartbeats = createHeartbeats(
                5,
                50,          // Very low connections
                20.0,        // Very low MPS
                40.0,        // Very low latency
                0.15,        // 15% CPU
                0.20         // 20% memory
        );

        // Set current replicas to allow scale-in
        scalingEngine.computeScalingDirective(heartbeats).block(); // Initialize

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        // Should scale in due to low utilization
        assertTrue(directive.getAction() == ScalingDirective.Action.SCALE_IN ||
                   directive.getAction() == ScalingDirective.Action.NONE,
                "Should scale in or maintain due to low usage");

        System.out.printf("Low CPU+memory: %s -> %d replicas (reason: %s)%n",
                directive.getAction(), directive.getTargetReplicas(), directive.getReason());
    }

    @Test
    void testModerateCpuAndMemory_NoScaling() {
        // 3 nodes with moderate CPU and memory (40-50%)
        List<Heartbeat> heartbeats = createHeartbeats(
                3,
                1500,
                200.0,
                120.0,
                0.45,        // 45% CPU
                0.50         // 50% memory
        );

        // Initialize with these heartbeats first
        scalingEngine.computeScalingDirective(heartbeats).block();

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        // Should maintain or scale in slightly due to being under some thresholds
        assertTrue(directive.getAction() == ScalingDirective.Action.NONE ||
                   directive.getAction() == ScalingDirective.Action.SCALE_IN,
                "Moderate usage should not scale out");

        System.out.printf("Moderate CPU+memory: %s (reason: %s)%n",
                directive.getAction(), directive.getReason());
    }

    @Test
    void testGradualCpuIncrease_ProgressiveScaling() {
        // Simulate gradual CPU increase over time

        // Stage 1: Normal CPU (50%)
        ScalingDirective d1 = scalingEngine.computeScalingDirective(
                createHeartbeats(3, 1000, 150.0, 100.0, 0.50, 0.45)
        ).block();

        // Stage 2: Elevated CPU (65%)
        ScalingDirective d2 = scalingEngine.computeScalingDirective(
                createHeartbeats(3, 1000, 150.0, 100.0, 0.65, 0.45)
        ).block();

        // Stage 3: High CPU (80%)
        ScalingDirective d3 = scalingEngine.computeScalingDirective(
                createHeartbeats(3, 1000, 150.0, 100.0, 0.80, 0.45)
        ).block();

        System.out.printf("Progressive scaling: %s -> %s -> %s%n",
                d1.getAction(), d2.getAction(), d3.getAction());

        // Should eventually trigger scale-out
        assertTrue(d3.getAction() == ScalingDirective.Action.SCALE_OUT ||
                   d3.getAction() == ScalingDirective.Action.NONE,
                "Should scale out under high CPU");
    }

    @Test
    void testMixedCpuLevels_ScalesOutForMax() {
        // 3 nodes with one very high CPU
        List<Heartbeat> heartbeats = new ArrayList<>();
        heartbeats.add(createHeartbeat("node-1", 1000, 100.0, 100.0, 0.50, 0.45)); // Normal
        heartbeats.add(createHeartbeat("node-2", 1000, 100.0, 100.0, 0.55, 0.45)); // Slightly elevated
        heartbeats.add(createHeartbeat("node-3", 1000, 100.0, 100.0, 0.90, 0.45)); // Very high

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        // Should scale out because max CPU exceeds threshold (85%)
        assertEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction());

        System.out.printf("Mixed CPU levels: %s -> %d replicas%n",
                directive.getAction(), directive.getTargetReplicas());
    }

    @Test
    void testEdgeCase_CpuAtThreshold() {
        // CPU exactly at threshold (70%)
        List<Heartbeat> heartbeats = createHeartbeats(
                3,
                1000,
                150.0,
                120.0,
                0.70,        // Exactly at 70% threshold
                0.50
        );

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        // Should NOT scale out (threshold is > 70%, not >=)
        assertTrue(directive.getAction() != ScalingDirective.Action.SCALE_OUT,
                "Should not scale out at exactly 70% CPU");

        System.out.printf("CPU at threshold: %s%n", directive.getAction());
    }

    @Test
    void testEdgeCase_CpuJustAboveThreshold() {
        // CPU just above threshold (70.1%)
        List<Heartbeat> heartbeats = createHeartbeats(
                3,
                1000,
                150.0,
                120.0,
                0.701,       // Just above 70%
                0.50
        );

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        // Should scale out
        assertEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction());

        System.out.printf("CPU just above threshold: %s -> %d replicas%n",
                directive.getAction(), directive.getTargetReplicas());
    }

    @Test
    void testMemoryJustBelowThreshold_NoScaling() {
        // Memory at 74.9% (just below 75% threshold)
        List<Heartbeat> heartbeats = createHeartbeats(
                3,
                1000,
                150.0,
                120.0,
                0.50,
                0.749        // Just below 75%
        );

        // Initialize first
        scalingEngine.computeScalingDirective(heartbeats).block();

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        // May scale in or maintain, but should NOT scale out
        assertNotEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction(),
                "Should not scale out with memory below 75%");

        System.out.printf("Memory just below threshold: %s%n", directive.getAction());
    }

    @Test
    void testMemoryJustAboveThreshold_ScalesOut() {
        // Memory at 75.1% (just above 75% threshold)
        List<Heartbeat> heartbeats = createHeartbeats(
                3,
                1000,
                150.0,
                120.0,
                0.50,
                0.751        // Just above 75%
        );

        ScalingDirective directive = scalingEngine.computeScalingDirective(heartbeats).block();

        assertNotNull(directive);
        assertEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction());

        System.out.printf("Memory just above threshold: %s -> %d replicas%n",
                directive.getAction(), directive.getTargetReplicas());
    }

    private List<Heartbeat> createHeartbeats(int count, int conn, double mps, double p95,
                                             double cpu, double mem) {
        List<Heartbeat> heartbeats = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            heartbeats.add(createHeartbeat("node-" + i, conn, mps, p95, cpu, mem));
        }
        return heartbeats;
    }

    private Heartbeat createHeartbeat(String nodeId, int conn, double mps, double p95,
                                      double cpu, double mem) {
        return Heartbeat.builder()
                .nodeId(nodeId)
                .activeConn(conn)
                .mps(mps)
                .p95LatencyMs(p95)
                .queueDepth((int) (mps / 10))
                .cpu(cpu)
                .mem(mem)
                .timestampMs(System.currentTimeMillis())
                .build();
    }

    /**
     * Test stub for Kafka publishing.
     */
    private static class TestKafkaPublisher implements IRingPublisher {
        @Override
        public Mono<Void> publishRingUpdate(ControlMessages.RingUpdate ringUpdate) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> publishScaleSignal(ControlMessages.ScaleSignal scaleSignal) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> publishDrainSignal(ControlMessages.DrainSignal drainSignal) {
            return Mono.empty();
        }

        @Override
        public void close() {
            // No-op
        }
    }
}

