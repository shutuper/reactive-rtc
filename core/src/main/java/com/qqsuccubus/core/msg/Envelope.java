package com.qqsuccubus.core.msg;

import lombok.*;

/**
 * Universal message envelope for all WebSocket messages.
 * <p>
 * <b>Ordering guarantee:</b> Messages with the same {@code toClientId} are delivered
 * in order (per-key ordering via Kafka partition key).
 * </p>
 * <p>
 * <b>At-least-once semantics:</b> Messages may be delivered multiple times (e.g., during
 * reconnect/resume). Clients must deduplicate using {@code msgId}.
 * </p>
 * <p>
 * <b>Idempotency:</b> Applications should handle duplicate {@code msgId} gracefully
 * (e.g., store last N seen msgIds).
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Envelope {
    /**
     * Globally unique message ID (UUID or snowflake).
     * Used for deduplication and ack tracking.
     */
    String msgId;

    /**
     * Sender user ID (optional, may be system).
     */
    String from;

    /**
     * Target user ID. This is the partition key for Kafka.
     */
    String toClientId;

    /**
     * Message type (e.g., "chat", "notification", "control").
     */
    String type;

    /**
     * JSON-encoded payload (application-specific).
     */
    String payloadJson;

    /**
     * client specific message offset.
     */
    int offset = -1;

    /**
     * Timestamp when this message was created (epoch millis).
     */
    long ts;

    String nodeId;
}











