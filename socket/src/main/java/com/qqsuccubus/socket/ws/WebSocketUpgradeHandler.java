package com.qqsuccubus.socket.ws;

import com.qqsuccubus.socket.config.SocketConfig;
import com.qqsuccubus.socket.drain.DrainService;
import com.qqsuccubus.socket.kafka.IKafkaService;
import com.qqsuccubus.socket.metrics.MetricsService;
import com.qqsuccubus.socket.session.ISessionManager;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(WebSocketUpgradeHandler.class);

    private final WebSocketHandler wsHandler;
    private final DrainService drainService;

    public WebSocketUpgradeHandler(
            SocketConfig config,
            ISessionManager sessionManager,
            IKafkaService kafkaService,
            MetricsService metricsService,
            DrainService drainService
    ) {
        this.wsHandler = new WebSocketHandler(config, sessionManager, kafkaService, metricsService);
        this.drainService = drainService;
    }

    /**
     * Handles WebSocket upgrade request.
     *
     * @param req HTTP request
     * @param res HTTP response
     * @return Mono for upgrade
     */
    public Mono<Void> handle(HttpServerRequest req, HttpServerResponse res) {
        // Reject new connections if node is draining
        if (drainService.isDraining()) {
            log.warn("Rejecting new WebSocket connection - node is draining");
            return res.status(503)
                .sendString(Mono.just("Service unavailable - node is draining"))
                .then();
        }

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

