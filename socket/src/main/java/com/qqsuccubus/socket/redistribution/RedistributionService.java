package com.qqsuccubus.socket.redistribution;

import com.qqsuccubus.socket.config.SocketConfig;
import com.qqsuccubus.socket.ring.RingService;
import com.qqsuccubus.socket.session.ISessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service responsible for gradual client redistribution after ring updates.
 * <p>
 * When the consistent hash ring is updated, some clients may now hash to
 * different servers. This service gradually disconnects those clients over
 * a configurable time window (2-5 minutes) to allow for graceful reconnection.
 * </p>
 * <p>
 * Strategy:
 * 1. Calculate all clients that need to be disconnected upfront
 * 2. Spread disconnections evenly over the duration
 * 3. Every 5 seconds, disconnect a batch of equal size
 * </p>
 */
public class RedistributionService {
    private static final Logger log = LoggerFactory.getLogger(RedistributionService.class);

    private final SocketConfig config;
    private final ISessionManager sessionManager;
    private final RingService ringService;

    // Redistribution configuration
    private static final Duration MIN_REDISTRIBUTION_DURATION = Duration.ofMinutes(2);
    private static final Duration MAX_REDISTRIBUTION_DURATION = Duration.ofMinutes(5);

    // Interval between disconnection batches
    private static final Duration DISCONNECT_INTERVAL = Duration.ofSeconds(5);

    // Current redistribution process (null if none active)
    private final AtomicReference<Disposable> currentRedistribution = new AtomicReference<>();

    public RedistributionService(SocketConfig config, ISessionManager sessionManager, RingService ringService) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.ringService = ringService;
    }

    /**
     * Starts a new gradual redistribution process.
     * <p>
     * If a redistribution is already in progress, it will be cancelled
     * and a new one will start.
     * </p>
     *
     * @param reason Reason for redistribution (for logging)
     */
    public void startRedistribution(String reason) {
        // Cancel any existing redistribution
        Disposable existing = currentRedistribution.getAndSet(null);
        if (existing != null && !existing.isDisposed()) {
            log.info("Cancelling existing redistribution process");
            existing.dispose();
        }

        // Random duration between 2-5 minutes for gradual disconnect
        long redistributionSeconds = ThreadLocalRandom.current()
            .nextLong(
                MIN_REDISTRIBUTION_DURATION.getSeconds(),
                MAX_REDISTRIBUTION_DURATION.getSeconds()
            );

        log.info("Starting client redistribution: reason='{}', duration={}s",
            reason, redistributionSeconds);

        String currentNodeId = config.getNodeId();

        // Start redistribution: calculate clients to disconnect, then spread evenly
        Disposable newRedistribution = calculateClientsToDisconnect(currentNodeId)
            .flatMap(clientsToDisconnect -> {
                if (clientsToDisconnect.isEmpty()) {
                    log.info("No clients need redistribution");
                    return Mono.empty();
                }

                int totalClients = clientsToDisconnect.size();
                int totalIntervals = (int) (redistributionSeconds / DISCONNECT_INTERVAL.getSeconds());
                int clientsPerBatch = Math.max(1, (totalClients + totalIntervals - 1) / totalIntervals);

                log.info("Redistributing {} clients over {} intervals ({} clients per batch)",
                    totalClients, totalIntervals, clientsPerBatch);

                // Track current index in the clients list
                AtomicInteger currentIndex = new AtomicInteger(0);

                // Disconnect one batch every DISCONNECT_INTERVAL
                return Flux.interval(Duration.ZERO, DISCONNECT_INTERVAL)
                    .take(totalIntervals)
                    .flatMap(tick -> {
                        int startIndex = currentIndex.get();
                        int endIndex = Math.min(startIndex + clientsPerBatch, totalClients);

                        if (startIndex >= totalClients) {
                            return Mono.empty();
                        }

                        List<String> batch = clientsToDisconnect.subList(startIndex, endIndex);
                        currentIndex.set(endIndex);

                        log.info("Disconnecting batch at index {}-{}: {} clients", startIndex, endIndex - 1, batch.size());

                        return Flux.fromIterable(batch)
                            .flatMap(clientId -> disconnectClient(clientId, "Ring update - reconnect to correct server"))
                            .then();
                    })
                    .then();
            })
            .doOnSuccess(v -> {
                log.info("Redistribution process completed successfully");
                currentRedistribution.compareAndSet(currentRedistribution.get(), null);
            })
            .doOnError(error -> {
                log.error("Redistribution process failed with error", error);
                currentRedistribution.compareAndSet(currentRedistribution.get(), null);
            })
            .subscribe(
                v -> {},
                error -> log.error("Redistribution error", error),
                () -> log.info("Redistribution complete")
            );

        currentRedistribution.set(newRedistribution);
    }

    /**
     * Calculates all clients that need to be disconnected.
     * <p>
     * This checks all active clients against the current hash ring
     * and returns a list of clientIds that should be on different servers.
     * </p>
     *
     * @param currentNodeId Current node ID
     * @return Mono with list of client IDs to disconnect
     */
    private Mono<List<String>> calculateClientsToDisconnect(String currentNodeId) {
        return Mono.fromCallable(() -> {
            Set<String> activeClientIds = sessionManager.getActiveClientIds();
            log.info("Calculating redistribution for {} active clients", activeClientIds.size());

            List<String> clientsToDisconnect = new ArrayList<>();

            for (String clientId : activeClientIds) {
                String targetNode = ringService.resolveTargetNode(clientId);

                if (targetNode == null) {
                    log.warn("Could not resolve target node for client {} - skipping", clientId);
                    continue;
                }

                if (!targetNode.equals(currentNodeId)) {
                    log.debug("Client {} should be on node {} (currently on {})",
                        clientId, targetNode, currentNodeId);
                    clientsToDisconnect.add(clientId);
                }
            }

            log.info("Identified {} clients for redistribution out of {} total", clientsToDisconnect.size(), activeClientIds.size());

            return clientsToDisconnect;
        });
    }

    /**
     * Gracefully disconnects a client with a reason.
     *
     * @param clientId Client to disconnect
     * @param reason   Reason for disconnection
     * @return Mono completing when client is disconnected
     */
    private Mono<Void> disconnectClient(String clientId, String reason) {
        log.info("Gracefully disconnecting client {}: {}", clientId, reason);

        return sessionManager.removeSession(clientId)
            .doOnSuccess(v -> log.info("Client {} disconnected successfully", clientId))
            .doOnError(error -> log.error("Failed to disconnect client {}", clientId, error))
            .onErrorResume(error -> Mono.empty()); // Continue with other clients even if one fails
    }

    /**
     * Stops any ongoing redistribution process.
     */
    public void stop() {
        Disposable existing = currentRedistribution.getAndSet(null);
        if (existing != null && !existing.isDisposed()) {
            log.info("Stopping redistribution service and cancelling active redistribution");
            existing.dispose();
        }
    }

    /**
     * Checks if a redistribution is currently in progress.
     *
     * @return true if redistribution is active
     */
    public boolean isRedistributing() {
        Disposable current = currentRedistribution.get();
        return current != null && !current.isDisposed();
    }
}

