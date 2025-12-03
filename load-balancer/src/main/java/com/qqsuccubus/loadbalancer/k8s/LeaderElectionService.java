package com.qqsuccubus.loadbalancer.k8s;

import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpec;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leader election service using Kubernetes Lease API.
 * <p>
 * Implements leader election with 15-second lease duration and automatic renewal.
 * Only the master node (leader) can perform operations like:
 * - Recomputing hash weights
 * - Consuming socket node heartbeats
 * - Sending scale decisions
 * </p>
 * <p>
 * Non-leader nodes will continuously attempt to acquire the lease if the leader fails.
 * </p>
 */
public class LeaderElectionService {
    private static final Logger log = LoggerFactory.getLogger(LeaderElectionService.class);

    private static final String LEASE_NAME = "load-balancer-leader";
    private static final int LEASE_DURATION_SECONDS = 15;
    private static final int ACQUIRE_RETRY_INTERVAL_SECONDS = 10; // Check every 5 seconds if not leader

    private final KubernetesClient client;
    private final String namespace;
    private final String podName;
    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private Disposable leaseRenewalTask;

    public LeaderElectionService(String namespace, String podName) {
        this.client = new KubernetesClientBuilder().build();
        this.namespace = namespace;
        this.podName = podName;

        log.info("Leader election service initialized for pod {} in namespace {}", podName, namespace);
    }

    /**
     * Starts the leader election process.
     * <p>
     * Continuously attempts to acquire or renew the lease.
     * </p>
     *
     * @return Disposable to cancel the election process
     */
    public Disposable startLeaderElection() {
        log.info("Starting leader election for pod {}", podName);

        leaseRenewalTask = Flux.interval(Duration.ofSeconds(ACQUIRE_RETRY_INTERVAL_SECONDS))
            .flatMap(tick -> attemptLeadershipAcquisition())
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();

        return leaseRenewalTask;
    }

    /**
     * Attempts to acquire or renew leadership.
     *
     * @return Mono<Void> completing when attempt is done
     */
    private Mono<Void> attemptLeadershipAcquisition() {
        return Mono.fromCallable(() -> {
                try {
                    Lease existingLease = client.leases()
                        .inNamespace(namespace)
                        .withName(LEASE_NAME)
                        .get();

                    if (existingLease == null) {
                        // No lease exists, create it
                        return createLease();
                    } else {
                        // Lease exists, try to acquire or renew it
                        return acquireOrRenewLease(existingLease);
                    }
                } catch (Exception e) {
                    log.error("Error during leader election attempt", e);
                    isLeader.set(false);
                    return null;
                }
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    /**
     * Gets current time as ZonedDateTime in UTC.
     *
     * @return Current ZonedDateTime in UTC
     */
    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Converts ZonedDateTime to Instant for duration calculations.
     *
     * @param zonedDateTime ZonedDateTime from Kubernetes lease
     * @return Instant
     */
    private Instant toInstant(ZonedDateTime zonedDateTime) {
        if (zonedDateTime == null) {
            return Instant.EPOCH;
        }
        return zonedDateTime.toInstant();
    }

    /**
     * Creates a new lease with this pod as the holder.
     *
     * @return Created Lease
     */
    private Lease createLease() {
        try {
            ZonedDateTime now = now();

            LeaseSpec spec = new LeaseSpecBuilder()
                .withHolderIdentity(podName)
                .withLeaseDurationSeconds(LEASE_DURATION_SECONDS)
                .withAcquireTime(now)
                .withRenewTime(now)
                .build();

            Lease lease = new LeaseBuilder()
                .withNewMetadata()
                .withName(LEASE_NAME)
                .withNamespace(namespace)
                .endMetadata()
                .withSpec(spec)
                .build();

            Lease created = client.leases()
                .inNamespace(namespace)
                .resource(lease)
                .create();

            isLeader.set(true);
            log.info("✓ Leadership ACQUIRED by pod {} - this node is now the MASTER", podName);
            return created;

        } catch (Exception e) {
            log.warn("Failed to create lease (another pod may have created it): {}", e.getMessage());
            isLeader.set(false);
            return null;
        }
    }

    /**
     * Attempts to acquire or renew an existing lease.
     *
     * @param existingLease Current lease
     * @return Updated Lease or null
     */
    private Lease acquireOrRenewLease(Lease existingLease) {
        LeaseSpec spec = existingLease.getSpec();
        String currentHolder = spec.getHolderIdentity();
        ZonedDateTime renewTime = spec.getRenewTime();

        // Calculate time since last renewal
        Instant renewInstant = toInstant(renewTime);
        Instant nowInstant = Instant.now();
        long secondsSinceRenewal = Duration.between(renewInstant, nowInstant).getSeconds();

        boolean leaseExpired = secondsSinceRenewal > LEASE_DURATION_SECONDS;

        if (podName.equals(currentHolder)) {
            // This pod is the current leader, renew the lease
            return renewLease(existingLease);
        } else if (leaseExpired) {
            // Lease has expired, try to acquire it
            log.info("Lease expired (held by {}), attempting to acquire leadership", currentHolder);
            return acquireLease(existingLease);
        } else {
            // Another pod holds a valid lease
            if (isLeader.get()) {
                log.warn("✗ Leadership LOST - another pod ({}) is now the master", currentHolder);
            }
            isLeader.set(false);
            log.debug("Pod {} is the leader (lease valid for {} more seconds)",
                currentHolder, LEASE_DURATION_SECONDS - secondsSinceRenewal);
            return null;
        }
    }

    /**
     * Renews the lease held by this pod.
     *
     * @param lease Current lease
     * @return Updated Lease
     */
    private Lease renewLease(Lease lease) {
        try {
            ZonedDateTime now = now();

            // Create updated spec with new renew time
            LeaseSpec updatedSpec = new LeaseSpecBuilder()
                .withHolderIdentity(lease.getSpec().getHolderIdentity())
                .withLeaseDurationSeconds(lease.getSpec().getLeaseDurationSeconds())
                .withAcquireTime(lease.getSpec().getAcquireTime())
                .withRenewTime(now)
                .build();

            lease.setSpec(updatedSpec);

            Lease updated = client.leases()
                .inNamespace(namespace)
                .resource(lease)
                .update();

            if (!isLeader.get()) {
                log.info("✓ Leadership RENEWED by pod {} - still the MASTER", podName);
            }
            isLeader.set(true);
            log.debug("Lease renewed by leader {}", podName);

            return updated;

        } catch (Exception e) {
            log.error("Failed to renew lease - may have lost leadership: {}", e.getMessage());
            isLeader.set(false);
            return null;
        }
    }

    /**
     * Attempts to acquire an expired lease.
     *
     * @param lease Expired lease
     * @return Updated Lease or null
     */
    private Lease acquireLease(Lease lease) {
        try {
            ZonedDateTime now = now();

            // Create new spec with this pod as holder
            LeaseSpec updatedSpec = new LeaseSpecBuilder()
                .withHolderIdentity(podName)
                .withLeaseDurationSeconds(LEASE_DURATION_SECONDS)
                .withAcquireTime(now)
                .withRenewTime(now)
                .build();

            lease.setSpec(updatedSpec);

            Lease updated = client.leases()
                .inNamespace(namespace)
                .resource(lease)
                .update();

            isLeader.set(true);
            log.info("✓ Leadership ACQUIRED by pod {} - this node is now the MASTER", podName);
            return updated;

        } catch (Exception e) {
            log.warn("Failed to acquire expired lease (another pod may have acquired it): {}", e.getMessage());
            isLeader.set(false);
            return null;
        }
    }

    /**
     * Checks if this pod is currently the leader.
     *
     * @return true if leader, false otherwise
     */
    public boolean isLeader() {
        return isLeader.get();
    }

    /**
     * Stops the leader election process and releases the lease if this pod is the leader.
     */
    public void stop() {
        if (leaseRenewalTask != null) {
            leaseRenewalTask.dispose();
        }

        if (isLeader.get()) {
            try {
                // Delete the lease to allow another pod to become leader immediately
                client.leases()
                    .inNamespace(namespace)
                    .withName(LEASE_NAME)
                    .delete();

                log.info("Lease released by pod {}", podName);
            } catch (Exception e) {
                log.warn("Failed to release lease during shutdown: {}", e.getMessage());
            }
        }

        client.close();
        log.info("Leader election service stopped");
    }
}
