package com.qqsuccubus.socket.session;

import com.qqsuccubus.core.msg.Envelope;
import lombok.Getter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents an active WebSocket session.
 */
@Getter
public class Session {
    private final String clientId;
    private final AtomicInteger lastOffset;
    private final Sinks.Many<Envelope> sink;

    public Session(String clientId, int lastOffset, Sinks.Many<Envelope> sink) {
        this.clientId = clientId;
        this.lastOffset = new AtomicInteger(lastOffset);
        this.sink = sink;
    }

    public Flux<Envelope> getOutboundFlux() {
        return sink.asFlux().doOnNext(envelope -> {
            if (envelope.getOffset() < 0) {
                envelope.setOffset(lastOffset.getAndIncrement());
            }
        });
    }
}
