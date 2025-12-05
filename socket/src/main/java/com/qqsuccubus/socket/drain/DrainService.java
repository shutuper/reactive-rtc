package com.qqsuccubus.socket.drain;

import com.qqsuccubus.socket.session.ISessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for gracefully draining connections during pod termination.
 * <p>
 * Implements a 5-minute draining process:
 * 1. Kubernetes calls /drain endpoint (via preStop hook)
 * 2. Socket node enters draining mode
 * 3. Rejects new connections
 * 4. Periodically disconnects existing connections in batches
 * 5. After 5 minutes (or all connections drained), Kubernetes terminates the pod
 * </p>
 */
public class DrainService {
    private static final Logger log = LoggerFactory.getLogger(DrainService.class);

    private static final int DRAIN_DURATION_MINUTES = 5;
    private static final int BATCH_DISCONNECT_INTERVAL_SECONDS = 2; // Disconnect a batch every 2 seconds

    private final ISessionManager sessionManager;
    private final AtomicBoolean isDraining = new AtomicBoolean(false);
    private final AtomicBoolean isDrainComplete = new AtomicBoolean(false);
    private final AtomicInteger remainingConnections = new AtomicInteger(0);

    private Disposable drainTask;

    public DrainService(ISessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Starts the draining process.
     * <p>
     * Called by Kubernetes preStop hook (via /drain endpoint).
     * </p>
     *
     * @return Mono completing when drain is started
     */
    public Mono<Void> startDrain() {
        if (isDraining.compareAndSet(false, true)) {
            log.warn("⚠️ DRAIN MODE ACTIVATED - Starting graceful connection draining (5 minutes)");

            Set<String> activeClientIds = sessionManager.getActiveClientIds();
            int totalConnections = activeClientIds.size();
            remainingConnections.set(totalConnections);

            log.info("Starting drain process for {} active connections", totalConnections);

            if (totalConnections == 0) {
                log.info("No active connections, drain complete immediately");
                isDrainComplete.set(true);
                return Mono.empty();
            }

            // Calculate how many connections to disconnect per batch
            // We want to drain all connections over 5 minutes with batches every 2 seconds
            int totalBatches = (DRAIN_DURATION_MINUTES * 60) / BATCH_DISCONNECT_INTERVAL_SECONDS; // 600 batches
            int connectionsPerBatch = Math.max(1, totalConnections / totalBatches);

            log.info("Drain plan: {} total batches, ~{} connections per batch, batch every {} seconds",
                totalBatches, connectionsPerBatch, BATCH_DISCONNECT_INTERVAL_SECONDS);

            // Start periodic draining
            drainTask = Flux.interval(Duration.ofSeconds(BATCH_DISCONNECT_INTERVAL_SECONDS))
                .take(totalBatches)
                .flatMap(tick -> drainBatch(connectionsPerBatch))
                .doOnComplete(() -> {
                    log.info("✅ Drain process complete - all connections gracefully closed");
                    isDrainComplete.set(true);
                })
                .then(drainBatch(100_000))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

            return Mono.empty();

        } else {
            log.warn("Drain already in progress");
            return Mono.empty();
        }
    }

    /**
     * Drains a batch of connections.
     *
     * @param batchSize Number of connections to disconnect
     * @return Mono completing when batch is drained
     */
    private Mono<Void> drainBatch(int batchSize) {
        return Mono.defer(() -> {
            Set<String> activeClientIds = sessionManager.getActiveClientIds();
            int remaining = activeClientIds.size();
            remainingConnections.set(remaining);

            if (remaining == 0) {
                log.info("All connections drained");
                isDrainComplete.set(true);
                return Mono.empty();
            }

            // Take up to batchSize connections to disconnect
            int toDisconnect = Math.min(batchSize, remaining);

            log.debug("Draining batch: disconnecting {} connections ({} remaining)",
                toDisconnect, remaining - toDisconnect);

            return Flux.fromIterable(activeClientIds)
                .take(toDisconnect)
                .flatMap(clientId -> sessionManager.removeSession(clientId)
                    .doOnSuccess(v -> log.debug("Gracefully closed connection for client {}", clientId))
                    .onErrorResume(err -> {
                        log.warn("Failed to close connection for client {}: {}",
                            clientId, err.getMessage());
                        return Mono.empty();
                    })
                )
                .then();
        });
    }

    /**
     * Checks if the node is currently draining.
     *
     * @return true if draining, false otherwise
     */
    public boolean isDraining() {
        return isDraining.get();
    }

    /**
     * Checks if the drain process is complete.
     *
     * @return true if drain is complete, false otherwise
     */
    public boolean isDrainComplete() {
        return isDrainComplete.get();
    }

    /**
     * Gets the number of remaining connections to drain.
     *
     * @return Number of active connections
     */
    public int getRemainingConnections() {
        return remainingConnections.get();
    }

    /**
     * Stops the drain process.
     */
    public void stop() {
        if (drainTask != null) {
            drainTask.dispose();
        }
        log.info("Drain service stopped");
    }
}








