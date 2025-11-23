package com.qqsuccubus.socket.kafka;

import com.qqsuccubus.core.msg.Envelope;
import reactor.core.publisher.Mono;

/**
 * Interface for Kafka operations (Dependency Inversion Principle).
 * <p>
 * Abstracts Kafka producer/consumer logic for testability.
 * </p>
 */
public interface IKafkaService {
    /**
     * Starts Kafka consumers.
     *
     * @return Mono completing when consumers are subscribed
     */
    Mono<Void> start();

    /**
     * Publishes a message to Kafka for two-hop relay.
     *
     * @param targetNodeId Target node identifier (partition key)
     * @param envelope     Message envelope
     * @return Mono completing when published
     */
    Mono<Void> publishRelay(String targetNodeId, Envelope envelope);

    /**
     * Stops Kafka consumers and producer.
     *
     * @return Mono completing when stopped
     */
    Mono<Void> stop();
}











