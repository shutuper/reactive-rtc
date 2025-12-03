package com.qqsuccubus.socket.session;

import com.qqsuccubus.core.msg.Envelope;
import lombok.Getter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.netty.http.websocket.WebsocketOutbound;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents an active WebSocket session.
 */
@Getter
public class Session {
    private final String clientId;
    private final AtomicInteger lastOffset;
    private final Sinks.Many<Envelope> sink;
    private final WebsocketOutbound outbound;

    public Session(String clientId, int lastOffset, Sinks.Many<Envelope> sink, WebsocketOutbound outbound) {
        this.clientId = clientId;
        this.lastOffset = new AtomicInteger(lastOffset);
        this.sink = sink;
        this.outbound = outbound;
    }

    public Flux<Envelope> getOutboundFlux() {
        return sink.asFlux().doOnNext(envelope -> {
            if (envelope.getOffset() < 0) {
                envelope.setOffset(lastOffset.getAndIncrement());
            }
        });
    }
}
