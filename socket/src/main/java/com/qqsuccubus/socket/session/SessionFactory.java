package com.qqsuccubus.socket.session;

import com.qqsuccubus.core.msg.Envelope;
import com.qqsuccubus.socket.config.SocketConfig;
import reactor.core.publisher.Sinks;
import reactor.netty.http.websocket.WebsocketOutbound;

/**
 * Factory for creating Session objects (Single Responsibility Principle).
 * <p>
 * Separated from SessionManager to isolate session creation logic.
 * </p>
 */
public class SessionFactory {
    private final SocketConfig config;

    public SessionFactory(SocketConfig config) {
        this.config = config;
    }

    /**
     * Creates a new session instance.
     *
     * @param userId       User identifier
     * @param resumeOffset last offset
     * @param outbound websocket outbound channel
     * @return Session instance
     */
    public Session createSession(String userId, int resumeOffset, WebsocketOutbound outbound) {
        // Create outbound sink with backpressure buffer
        Sinks.Many<Envelope> sink = Sinks.many().multicast().onBackpressureBuffer(
            config.getPerConnBufferSize(), false
        );

        return new Session(userId, resumeOffset, sink, outbound);
    }

}











