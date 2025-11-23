package com.qqsuccubus.socket.http;

import com.qqsuccubus.socket.config.SocketConfig;
import com.qqsuccubus.socket.kafka.IKafkaService;
import com.qqsuccubus.socket.metrics.MetricsService;
import com.qqsuccubus.socket.metrics.PrometheusMetricsExporter;
import com.qqsuccubus.socket.session.ISessionManager;
import com.qqsuccubus.socket.ws.WebSocketUpgradeHandler;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;

import java.time.Duration;
import java.util.function.Function;

/**
 * HTTP server for health checks, metrics, and WebSocket upgrades.
 */
@RequiredArgsConstructor
public class HttpServer {

    private final SocketConfig config;
    private final ISessionManager sessionManager;
    private final IKafkaService kafkaService;
    private final MetricsService metricsService;
    private final PrometheusMetricsExporter metricsExporter;
    private DisposableServer server;

    /**
     * Starts the HTTP server.
     *
     * @return Mono<Integer> of the bound port
     */
    public Mono<Integer> start() {
        // Create upgrade handler for proper param extraction
        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(
            config, sessionManager, kafkaService, metricsService
        );

        server = reactor.netty.http.server.HttpServer.create()
            .port(config.getHttpPort())
            .option(ChannelOption.SO_REUSEADDR, true)
            .metrics(true, Function.identity())
            .route(routes -> routes
                // Health check endpoint
                .get("/healthz", (req, res) -> res.status(200).sendString(Mono.just("OK")))
                // Metrics endpoint with Prometheus scraping
                .get("/metrics", (req, res) ->
                    res.header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                        .sendString(Mono.just(metricsExporter.scrape()))
                )
                // WebSocket upgrade endpoint with param extraction
                .get("/ws", upgradeHandler::handle)
            )
            .bindNow();

        return Mono.just(server.port());
    }

    public void stop() {
        server.disposeNow(Duration.ofSeconds(30));
    }
}
