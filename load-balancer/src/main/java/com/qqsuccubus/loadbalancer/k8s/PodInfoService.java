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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for retrieving pod information from Kubernetes.
 * Maintains a cache of pod name -> pod IP mappings for socket nodes.
 */
public class PodInfoService {
    private static final Logger log = LoggerFactory.getLogger(PodInfoService.class);

    private final KubernetesClient client;
    private final String namespace;
    
    // Cache: podName -> podIP
    private final Map<String, String> podIpCache = new ConcurrentHashMap<>();

    public PodInfoService(String namespace) {
        this.client = new KubernetesClientBuilder().build();
        this.namespace = namespace;
        log.info("Pod info service initialized for namespace {}", namespace);
    }

    public PodInfoService(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
        log.info("Pod info service initialized for namespace {}", namespace);
    }

    /**
     * Refreshes the pod IP cache by querying Kubernetes API.
     * 
     * @return Mono completing when cache is refreshed
     */
    public Mono<Map<String, String>> refreshPodIpCache() {
        return Mono.fromCallable(() -> {
            try {
                List<Pod> socketPods = client.pods()
                    .inNamespace(namespace)
                    .withLabel("app", "socket")
                    .list()
                    .getItems();

                Map<String, String> newCache = socketPods.stream()
                    .filter(pod -> pod.getStatus() != null && pod.getStatus().getPodIP() != null)
                    .filter(pod -> "Running".equals(pod.getStatus().getPhase()))
                    .collect(Collectors.toMap(
                        pod -> pod.getMetadata().getName(),
                        pod -> pod.getStatus().getPodIP(),
                        (existing, replacement) -> replacement
                    ));

                // Update cache
                podIpCache.clear();
                podIpCache.putAll(newCache);

                log.debug("Refreshed pod IP cache: {} pods", newCache.size());
                return newCache;

            } catch (Exception e) {
                log.error("Failed to refresh pod IP cache", e);
                throw e;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Gets the IP address for a pod by name.
     * First checks cache, then queries Kubernetes if not found.
     * 
     * @param podName Pod name
     * @return Pod IP or null if not found
     */
    public String getPodIp(String podName) {
        // Check cache first
        String cachedIp = podIpCache.get(podName);
        if (cachedIp != null) {
            return cachedIp;
        }

        // Query Kubernetes synchronously for immediate response
        try {
            Pod pod = client.pods()
                .inNamespace(namespace)
                .withName(podName)
                .get();

            if (pod != null && pod.getStatus() != null && pod.getStatus().getPodIP() != null) {
                String ip = pod.getStatus().getPodIP();
                podIpCache.put(podName, ip);
                return ip;
            }
        } catch (Exception e) {
            log.warn("Failed to get IP for pod {}: {}", podName, e.getMessage());
        }
        return null;
    }

    /**
     * Gets all cached pod IPs.
     * 
     * @return Map of podName -> podIP
     */
    public Map<String, String> getCachedPodIps() {
        return Map.copyOf(podIpCache);
    }

    /**
     * Updates cache with a new pod IP.
     * Called when a new node registers via heartbeat.
     * 
     * @param podName Pod name
     * @param podIp Pod IP
     */
    public void updatePodIp(String podName, String podIp) {
        if (podName != null && podIp != null) {
            podIpCache.put(podName, podIp);
            log.debug("Updated pod IP cache: {} -> {}", podName, podIp);
        }
    }

    /**
     * Removes a pod from the cache.
     * Called when a node is removed.
     * 
     * @param podName Pod name to remove
     */
    public void removePod(String podName) {
        podIpCache.remove(podName);
        log.debug("Removed pod from cache: {}", podName);
    }

    /**
     * Closes the Kubernetes client.
     */
    public void close() {
        client.close();
        log.info("Pod info service closed");
    }
}

