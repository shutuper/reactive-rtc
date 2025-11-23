package com.qqsuccubus.loadbalancer.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prometheus metrics exporter for load-balancer.
 */
public class PrometheusMetricsExporter {
    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsExporter.class);

    private final MeterRegistry registry;

    public PrometheusMetricsExporter(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Scrapes current metrics in Prometheus text format.
     *
     * @return Prometheus-formatted metrics string
     */
    public String scrape() {
        StringBuilder sb = new StringBuilder();

        // Export all meters
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName().replace('.', '_');
            String type = determineType(meter.getId().getType().name());

            sb.append("# HELP ").append(name).append(" ");
            if (meter.getId().getDescription() != null) {
                sb.append(meter.getId().getDescription());
            }
            sb.append("\n");
            sb.append("# TYPE ").append(name).append(" ").append(type).append("\n");

            meter.measure().forEach(measurement -> {
                sb.append(name);

                // Add tags
                if (!meter.getId().getTags().isEmpty()) {
                    sb.append("{");
                    meter.getId().getTags().forEach(tag ->
                            sb.append(tag.getKey()).append("=\"").append(tag.getValue()).append("\",")
                    );
                    sb.setLength(sb.length() - 1); // Remove trailing comma
                    sb.append("}");
                }

                sb.append(" ").append(measurement.getValue()).append("\n");
            });
            sb.append("\n");
        }

        return sb.toString();
    }

    private String determineType(String meterType) {
        return switch (meterType) {
            case "COUNTER" -> "counter";
            case "GAUGE" -> "gauge";
            case "TIMER", "DISTRIBUTION_SUMMARY" -> "histogram";
            case "LONG_TASK_TIMER" -> "gauge";
            default -> "untyped";
        };
    }
}











