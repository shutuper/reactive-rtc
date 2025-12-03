package com.qqsuccubus.loadbalancer.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for updating pod deletion costs in Kubernetes.
 * <p>
 * Pod deletion cost is an annotation that influences which pods Kubernetes chooses
 * to delete first during scale-down operations. Lower cost = deleted first.
 * </p>
 * <p>
 * This service allows the load balancer to update socket node pods with deletion costs
 * based on their active connection counts, ensuring least-loaded pods are removed first.
 * </p>
 */
public class PodDeletionCostService {
    private static final Logger log = LoggerFactory.getLogger(PodDeletionCostService.class);

    private static final String DELETION_COST_ANNOTATION = "controller.kubernetes.io/pod-deletion-cost";

    private final KubernetesClient client;
    private final String namespace;

    public PodDeletionCostService(String namespace) {
        this.client = new KubernetesClientBuilder().build();
        this.namespace = namespace;
        log.info("Pod deletion cost service initialized for namespace {}", namespace);
    }

    /**
     * Updates pod deletion costs for socket nodes based on their active connections.
     * <p>
     * Formula: deletionCost = activeConnections
     * - Pods with fewer connections will have lower costs and be deleted first
     * - Pods with more connections will have higher costs and be deleted last
     * </p>
     *
     * @param nodeConnectionCounts Map of nodeId -> activeConnectionCount
     * @return Mono completing when all updates are done
     */
    public Mono<Void> updatePodDeletionCosts(Map<String, Integer> nodeConnectionCounts) {
        if (nodeConnectionCounts == null || nodeConnectionCounts.isEmpty()) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            try {
                // Get all socket pods
                List<Pod> socketPods = client.pods()
                    .inNamespace(namespace)
                    .withLabel("app", "socket")
                    .list()
                    .getItems();

                for (Pod pod : socketPods) {
                    String podName = pod.getMetadata().getName();
                    Integer activeConnections = nodeConnectionCounts.get(podName);

                    if (activeConnections != null) {
                        updatePodDeletionCost(pod, activeConnections);
                    }
                }

                log.info("Updated pod deletion costs for {} socket pods", socketPods.size());
                return null;

            } catch (Exception e) {
                log.error("Failed to update pod deletion costs", e);
                throw e;
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }

    /**
     * Updates deletion cost annotation for a single pod.
     *
     * @param pod Pod to update
     * @param deletionCost Deletion cost value
     */
    private void updatePodDeletionCost(Pod pod, int deletionCost) {
        try {
            String podName = pod.getMetadata().getName();
            Map<String, String> annotations = pod.getMetadata().getAnnotations();

            String currentCost = annotations != null ? annotations.get(DELETION_COST_ANNOTATION) : null;
            String newCost = String.valueOf(deletionCost);

            // Only update if cost has changed to avoid unnecessary API calls
            if (!newCost.equals(currentCost)) {
                pod.getMetadata().getAnnotations().put(DELETION_COST_ANNOTATION, newCost);

                client.pods()
                    .inNamespace(namespace)
                    .resource(pod)
                    .update();

                log.debug("Updated pod {} deletion cost: {} -> {}", podName, currentCost, newCost);
            }

        } catch (Exception e) {
            log.warn("Failed to update deletion cost for pod {}: {}",
                pod.getMetadata().getName(), e.getMessage());
        }
    }

    /**
     * Gets the nodes sorted by deletion cost (ascending).
     * <p>
     * This can be used to identify which nodes will be deleted first.
     * </p>
     *
     * @param nodeConnectionCounts Map of nodeId -> activeConnectionCount
     * @return List of node IDs sorted by deletion cost (lowest first)
     */
    public List<String> getNodesSortedByDeletionCost(Map<String, Integer> nodeConnectionCounts) {
        return nodeConnectionCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Closes the Kubernetes client.
     */
    public void close() {
        client.close();
        log.info("Pod deletion cost service closed");
    }
}


