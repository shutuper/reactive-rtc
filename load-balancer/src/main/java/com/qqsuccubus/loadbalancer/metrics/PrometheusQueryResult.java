package com.qqsuccubus.loadbalancer.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Wrapper for Prometheus query results with convenient accessors.
 */
public class PrometheusQueryResult {
    private static final Logger log = LoggerFactory.getLogger(PrometheusQueryResult.class);

    private final List<PrometheusQueryService.PrometheusResult> results;

    private PrometheusQueryResult(List<PrometheusQueryService.PrometheusResult> results) {
        this.results = results != null ? results : Collections.emptyList();
    }

    /**
     * Creates a PrometheusQueryResult from a Prometheus API response.
     *
     * @param response Prometheus API response
     * @return PrometheusQueryResult instance
     */
    public static PrometheusQueryResult from(PrometheusQueryService.PrometheusResponse response) {
        if (response == null || response.getData() == null) {
            return empty();
        }
        return new PrometheusQueryResult(response.getData().getResult());
    }

    /**
     * Creates an empty result.
     *
     * @return Empty PrometheusQueryResult
     */
    public static PrometheusQueryResult empty() {
        return new PrometheusQueryResult(Collections.emptyList());
    }

    /**
     * Gets the single scalar value from the query result.
     * Use this for queries that return a single number (e.g., sum(), count()).
     *
     * @return Optional<Double> the value if present
     */
    public Optional<Double> getValue() {
        if (results.isEmpty()) {
            return Optional.empty();
        }

        try {
            PrometheusQueryService.PrometheusResult result = results.getFirst();
            if (result.getValue() == null || result.getValue().size() < 2) {
                return Optional.empty();
            }

            // Prometheus returns [timestamp, "value"]
            Object valueObj = result.getValue().get(1);
            if (valueObj instanceof String valueStr) {

                // Handle special values
                if ("NaN".equals(valueStr) || "Inf".equals(valueStr) || "+Inf".equals(valueStr)) {
                    log.warn("Received non-numeric value from Prometheus: {}", valueStr);
                    return Optional.of(0.0);
                }
                if ("-Inf".equals(valueStr)) {
                    return Optional.of(0.0);
                }

                return Optional.of(Double.parseDouble(valueStr));
            } else if (valueObj instanceof Number) {
                return Optional.of(((Number) valueObj).doubleValue());
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to parse Prometheus value: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Gets values grouped by a label.
     * Use this for queries with "by (label)" clause that return multiple series.
     *
     * @param labelName The label to group by (e.g., "node_id", "consumergroup")
     * @return Map of label value -> metric value
     */
    public Map<String, Double> getValuesByLabel(String labelName) {
        Map<String, Double> valuesByLabel = new HashMap<>();

        for (PrometheusQueryService.PrometheusResult result : results) {
            try {
                // Get label value
                if (result.getMetric() == null || !result.getMetric().containsKey(labelName)) {
                    log.debug("Result missing label '{}': {}", labelName, result.getMetric());
                    continue;
                }
                String labelValue = result.getMetric().get(labelName);

                // Get metric value
                if (result.getValue() == null || result.getValue().size() < 2) {
                    continue;
                }

                Object valueObj = result.getValue().get(1);
                double value;

                if (valueObj instanceof String valueStr) {

                    // Handle special values
                    if ("NaN".equals(valueStr) || "Inf".equals(valueStr) ||
                        "+Inf".equals(valueStr) || "-Inf".equals(valueStr)) {
                        value = 0.0;
                    } else {
                        value = Double.parseDouble(valueStr);
                    }
                } else if (valueObj instanceof Number) {
                    value = ((Number) valueObj).doubleValue();
                } else {
                    continue;
                }

                valuesByLabel.put(labelValue, value);
            } catch (Exception e) {
                log.error("Failed to parse result for label '{}': {}", labelName, e.getMessage());
            }
        }

        return valuesByLabel;
    }

    /**
     * Gets all results as a list.
     *
     * @return List of Prometheus results
     */
    public List<PrometheusQueryService.PrometheusResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    /**
     * Checks if the result is empty.
     *
     * @return true if no results
     */
    public boolean isEmpty() {
        return results.isEmpty();
    }

    /**
     * Gets the number of result series.
     *
     * @return Result count
     */
    public int size() {
        return results.size();
    }
}



