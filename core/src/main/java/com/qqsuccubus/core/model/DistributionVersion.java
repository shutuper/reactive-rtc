package com.qqsuccubus.core.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.time.Instant;

/**
 * Versioning metadata for the consistent hash ring.
 * <p>
 * Each time the ring topology changes (nodes join/leave or weights change),
 * the load-balancer issues a new version. Socket nodes track this version
 * to ensure they're routing with the latest topology.
 * </p>
 */
@Value
@Builder(toBuilder = true)
@With
public class DistributionVersion {
    /**
     * Monotonically increasing version number.
     * Higher version = newer ring configuration.
     */
    long version;

    /**
     * Timestamp when this version was issued.
     */
    Instant issuedAt;

    /**
     * Unique identifier for the version configuration (e.g., hash of node set).
     * Used to detect configuration drift.
     */
    String versionHash;
}


