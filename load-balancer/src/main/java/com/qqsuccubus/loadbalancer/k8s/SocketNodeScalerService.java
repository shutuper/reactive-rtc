package com.qqsuccubus.loadbalancer.k8s;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Service for scaling socket node workload (Deployment or StatefulSet) based on load-balancer decisions.
 * <p>
 * This service directly scales the socket workload in response to the
 * load-balancer's intelligent scaling decisions, which consider:
 * - CPU and memory utilization
 * - Active connections
 * - Message throughput
 * - P95 latency
 * - Kafka lag
 * </p>
 * <p>
 * Supports both Deployment (recommended) and StatefulSet workload types.
 * Deployment is preferred for better pod deletion cost selection during scale-down.
 * </p>
 */
public class SocketNodeScalerService {
    private static final Logger log = LoggerFactory.getLogger(SocketNodeScalerService.class);

    private final KubernetesClient client;
    private final String namespace;
    private final String workloadName;
    private final int minReplicas;
    private final int maxReplicas;

    public SocketNodeScalerService(String namespace, String workloadName, int minReplicas, int maxReplicas) {
        this.client = new KubernetesClientBuilder().build();
        this.namespace = namespace;
        this.workloadName = workloadName;
        this.minReplicas = minReplicas;
        this.maxReplicas = maxReplicas;

        log.info("Workload scaler initialized: namespace={}, workload={}, replicas=[{}, {}]",
            namespace, workloadName, minReplicas, maxReplicas);
    }

    /**
     * Scales the workload (StatefulSet or Deployment) based on scaling decision.
     *
     * @param scaleCount Number of replicas to add (positive) or remove (negative)
     * @param reason Reason for scaling
     * @return Mono completing when scaling is done
     */
    public Mono<Void> scaleSocketNodes(int scaleCount, String reason) {
        if (scaleCount == 0) {
            log.debug("No scaling needed");
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            try {
                // Try Deployment first (recommended for socket nodes)
                var deployment = client.apps().deployments()
                    .inNamespace(namespace)
                    .withName(workloadName)
                    .get();

                if (deployment != null) {
                    scaleDeploymentInternal(deployment, scaleCount, reason);
                    return null;
                }


                log.error("Workload {} not found in namespace {} (tried Deployment)", workloadName, namespace);
                return null;

            } catch (Exception e) {
                log.error("Failed to scale workload: {}", e.getMessage(), e);
                throw e;
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }

    private void scaleDeploymentInternal(Deployment deployment, int scaleCount, String reason) {
        Integer currentReplicas = deployment.getSpec().getReplicas();
        if (currentReplicas == null) {
            currentReplicas = 0;
        }

        int desiredReplicas = currentReplicas + scaleCount;
        desiredReplicas = Math.max(minReplicas, Math.min(maxReplicas, desiredReplicas));

        if (desiredReplicas == currentReplicas) {
            log.info("Scaling skipped: desired replicas ({}) same as current ({})",
                desiredReplicas, currentReplicas);
            return;
        }

        log.info("Scaling Deployment {} from {} to {} replicas. Reason: {}",
            workloadName, currentReplicas, desiredReplicas, reason);

        client.apps().deployments()
            .inNamespace(namespace)
            .withName(workloadName)
            .scale(desiredReplicas);

        log.info("✓ Successfully scaled Deployment {} to {} replicas", workloadName, desiredReplicas);
    }


    /**
     * Closes the Kubernetes client.
     */
    public void close() {
        client.close();
        log.info("Workload scaler service closed");
    }
}

