package com.qqsuccubus.loadbalancer.kafka;

import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.core.msg.Envelope;
import reactor.core.publisher.Mono;

/**
 * Interface for publishing ring updates (Dependency Inversion Principle).
 */
public interface IKafkaService {
    Mono<Void> publishRingUpdate(ControlMessages.RingUpdate ringUpdate);
    Mono<Void> publishScaleSignal(ControlMessages.ScaleSignal scaleSignal);
    Mono<Void> forwardMessage(Envelope envelope, String targetNodeId);
    Mono<Void> createTopicIfNotExists(String topicName, int partitions, short replicationFactor);
    void close();
}

