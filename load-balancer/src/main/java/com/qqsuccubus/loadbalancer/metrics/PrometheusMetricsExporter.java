package com.qqsuccubus.loadbalancer.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.Metrics;

/**
 * Prometheus metrics exporter with proper registry management.
 * Uses Micrometer's official Prometheus integration.
 */
public class PrometheusMetricsExporter {
    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsExporter.class);

    @Getter
    private final MeterRegistry registry;
    private final PrometheusMeterRegistry prometheusRegistry;

    public PrometheusMetricsExporter(String nodeId) {
        this.registry = Metrics.REGISTRY;

        // Create a Prometheus registry and add it to the global composite
        this.prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        if (registry instanceof CompositeMeterRegistry composite) {
            composite.add(prometheusRegistry);
        }

        registry.config().commonTags("node_id", nodeId);
        log.info("Metrics exporter initialized with global registry + Prometheus");
    }

    public String scrape() {
        return prometheusRegistry.scrape();
    }
}












