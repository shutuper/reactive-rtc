package com.qqsuccubus.socket.ws;

import com.qqsuccubus.socket.config.SocketConfig;
import com.qqsuccubus.socket.kafka.IKafkaService;
import com.qqsuccubus.socket.metrics.MetricsService;
import com.qqsuccubus.socket.session.ISessionManager;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.util.Collection;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Handles WebSocket upgrade with proper query parameter extraction.
 * <p>
 * Extracts userId and resumeToken from HTTP request before WebSocket upgrade,
 * ensuring clean parameter handling in production.
 * </p>
 */
public class WebSocketUpgradeHandler {

    private final WebSocketHandler wsHandler;

    public WebSocketUpgradeHandler(
            SocketConfig config,
            ISessionManager sessionManager,
            IKafkaService kafkaService,
            MetricsService metricsService
    ) {
        this.wsHandler = new WebSocketHandler(config, sessionManager, kafkaService, metricsService);
    }

    /**
     * Handles WebSocket upgrade request.
     *
     * @param req HTTP request
     * @param res HTTP response
     * @return Mono for upgrade
     */
    public Mono<Void> handle(HttpServerRequest req, HttpServerResponse res) {
        // Extract query parameters from HTTP request (before upgrade)
        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());

        String clientId = Stream.ofNullable(decoder.parameters().get("clientId"))
            .flatMap(Collection::stream).findFirst()
            .orElseGet(() -> UUID.randomUUID().toString());

        int resumeOffset = Stream.ofNullable(decoder.parameters().get("resumeOffset"))
            .flatMap(Collection::stream).findFirst().map(Integer::parseInt).orElse(0);

        // Upgrade to WebSocket with extracted parameters
        return res.sendWebsocket((inbound, outbound) ->
            wsHandler.handle(inbound, outbound, clientId, resumeOffset)
        );
    }
}

