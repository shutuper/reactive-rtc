package com.qqsuccubus.loadbalancer.ring;

import com.qqsuccubus.core.model.Heartbeat;
import com.qqsuccubus.core.model.NodeDescriptor;
import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production-like tests for RingManager under various load conditions.
 * <p>
 * Tests without Mockito for simplicity and Java version compatibility.
 * </p>
 */
class RingManagerLoadTest {

    private LBConfig config;
    private TestKafkaPublisher kafkaPublisher;
    private SimpleMeterRegistry meterRegistry;

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
                .scalingInterval(Duration.ofSeconds(60))
                .maxScaleStep(3)
                .heartbeatGrace(Duration.ofSeconds(90))
                .build();

        kafkaPublisher = new TestKafkaPublisher();
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void testLoadIncrease_TriggersRingRecomputation() {
        RingManager ringManager = new RingManager(config, kafkaPublisher, meterRegistry);

        // Start with 3 nodes at low load
        for (int i = 1; i <= 3; i++) {
            Heartbeat hb = createHeartbeat("node-" + i, 100, 50.0, 50.0);
            ringManager.processHeartbeat(hb).block();
        }

        assertEquals(3, ringManager.getPhysicalNodeCount());
        long version1 = ringManager.getRingSnapshot().getVersion().getVersion();

        // Simulate load increase - add 2 more nodes
        for (int i = 4; i <= 5; i++) {
            Heartbeat hb = createHeartbeat("node-" + i, 100, 50.0, 50.0);
            ringManager.processHeartbeat(hb).block();
        }

        assertEquals(5, ringManager.getPhysicalNodeCount());
        long version2 = ringManager.getRingSnapshot().getVersion().getVersion();

        // Verify ring version increased
        assertTrue(version2 > version1, "Ring version should increase when nodes join");

        // Verify Kafka published ring update
        assertTrue(kafkaPublisher.getRingUpdateCount() >= 2,
                "Should have published ring updates");
    }

    @Test
    void testHighVolumeUserDistribution_With100KUsers() {
        RingManager ringManager = new RingManager(config, kafkaPublisher, meterRegistry);

        // Add 10 nodes
        for (int i = 1; i <= 10; i++) {
            Heartbeat hb = createHeartbeat("node-" + i, 1000, 200.0, 100.0);
            ringManager.processHeartbeat(hb).block();
        }

        // Distribute 100,000 users
        Map<String, AtomicInteger> distribution = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            distribution.put("node-" + i, new AtomicInteger(0));
        }

        for (int i = 0; i < 100_000; i++) {
            String userId = "user-" + i;
            NodeDescriptor node = ringManager.resolveNode(userId);
            assertNotNull(node, "Every user should map to a node");
            distribution.get(node.getNodeId()).incrementAndGet();
        }

        // Verify distribution is balanced
        int expectedPerNode = 10_000;
        for (Map.Entry<String, AtomicInteger> entry : distribution.entrySet()) {
            int count = entry.getValue().get();
            double variance = Math.abs(count - expectedPerNode) / (double) expectedPerNode;

            System.out.printf("%s: %d users (%.1f%% variance)%n",
                    entry.getKey(), count, variance * 100);

            // Allow ±20% variance for realistic consistent hashing
            assertTrue(variance < 0.20,
                    String.format("%s got %d users, expected ~%d", entry.getKey(), count, expectedPerNode));
        }
    }

    @Test
    void testConcurrentHeartbeats_ThreadSafe() throws InterruptedException {
        RingManager ringManager = new RingManager(config, kafkaPublisher, meterRegistry);

        // 20 threads sending heartbeats
        Thread[] threads = new Thread[20];
        for (int i = 0; i < 20; i++) {
            final int nodeId = i + 1;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 5; j++) {
                    Heartbeat hb = createHeartbeat("node-" + nodeId, 1000, 200.0, 100.0);
                    ringManager.processHeartbeat(hb).block();
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(20, ringManager.getPhysicalNodeCount(),
                "All 20 nodes should be registered");
    }

    private Heartbeat createHeartbeat(String nodeId, int activeConn, double mps, double p95) {
        return Heartbeat.builder()
                .nodeId(nodeId)
                .activeConn(activeConn)
                .mps(mps)
                .p95LatencyMs(p95)
                .queueDepth((int) (mps / 10))
                .cpu(activeConn / 10000.0)
                .mem(activeConn / 5000.0)
                .timestampMs(System.currentTimeMillis())
                .build();
    }

    /**
     * Test stub for Kafka publishing (avoids actual Kafka connection).
     */
    private static class TestKafkaPublisher implements com.qqsuccubus.loadbalancer.kafka.IRingPublisher {
        private final AtomicInteger ringUpdateCount = new AtomicInteger(0);
        private final AtomicInteger scaleSignalCount = new AtomicInteger(0);

        @Override
        public Mono<Void> publishRingUpdate(ControlMessages.RingUpdate ringUpdate) {
            ringUpdateCount.incrementAndGet();
            return Mono.empty();
        }

        @Override
        public Mono<Void> publishScaleSignal(ControlMessages.ScaleSignal scaleSignal) {
            scaleSignalCount.incrementAndGet();
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

        public int getRingUpdateCount() {
            return ringUpdateCount.get();
        }

        public int getScaleSignalCount() {
            return scaleSignalCount.get();
        }
    }
}
