package com.qqsuccubus.loadbalancer.kafka;

import com.qqsuccubus.core.msg.ControlMessages;
import reactor.core.publisher.Mono;

/**
 * Interface for publishing ring updates (Dependency Inversion Principle).
 */
public interface IRingPublisher {
    Mono<Void> publishRingUpdate(ControlMessages.RingUpdate ringUpdate);
    Mono<Void> publishScaleSignal(ControlMessages.ScaleSignal scaleSignal);
    Mono<Void> publishDrainSignal(ControlMessages.DrainSignal drainSignal);
    void close();
}

