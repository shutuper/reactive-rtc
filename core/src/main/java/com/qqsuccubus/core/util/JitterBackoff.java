package com.qqsuccubus.core.util;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jittered exponential backoff calculator for graceful reconnect.
 * <p>
 * <b>Formula:</b> {@code t = min(max, base * phi^attempt) + uniform(0, jitterMax)}
 * <ul>
 *   <li>{@code base}: Initial delay</li>
 *   <li>{@code phi}: Growth factor (typically 2.0 for exponential)</li>
 *   <li>{@code max}: Maximum delay (cap)</li>
 *   <li>{@code jitterMax}: Maximum jitter to add</li>
 * </ul>
 * </p>
 * <p>
 * <b>Jitter rationale:</b> Prevents thundering herd when many clients reconnect simultaneously.
 * </p>
 */
public final class JitterBackoff {
    private JitterBackoff() {
    }

    /**
     * Computes the next backoff delay with jitter.
     *
     * @param attempt   Retry attempt number (0-based)
     * @param base      Base delay
     * @param max       Maximum delay (cap)
     * @param jitterMax Maximum jitter to add
     * @return Computed delay (base * 2^attempt + jitter, capped at max)
     */
    public static Duration next(int attempt, Duration base, Duration max, Duration jitterMax) {
        // Exponential component: base * 2^attempt
        long baseMs = base.toMillis();
        long expMs = baseMs * (1L << Math.min(attempt, 20)); // Cap exponent to avoid overflow

        // Cap at max
        long cappedMs = Math.min(expMs, max.toMillis());

        // Add jitter: uniform random in [0, jitterMax]
        long jitterMs = ThreadLocalRandom.current().nextLong(jitterMax.toMillis() + 1);

        return Duration.ofMillis(cappedMs + jitterMs);
    }

    /**
     * Convenience method with default parameters (phi=2, max=30s, jitter=5s).
     *
     * @param attempt Retry attempt number (0-based)
     * @return Computed delay
     */
    public static Duration next(int attempt) {
        return next(
                attempt,
                Duration.ofSeconds(1),   // base
                Duration.ofSeconds(30),  // max
                Duration.ofSeconds(5)    // jitterMax
        );
    }
}











