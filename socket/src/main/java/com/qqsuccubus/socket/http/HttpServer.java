package com.qqsuccubus.socket.http;

import com.qqsuccubus.socket.config.SocketConfig;
import com.qqsuccubus.socket.drain.DrainService;
import com.qqsuccubus.socket.kafka.IKafkaService;
import com.qqsuccubus.socket.metrics.MetricsService;
import com.qqsuccubus.socket.metrics.PrometheusMetricsExporter;
import com.qqsuccubus.socket.session.ISessionManager;
import com.qqsuccubus.socket.ws.WebSocketUpgradeHandler;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;

import java.time.Duration;
import java.util.function.Function;

/**
 * HTTP server for health checks, metrics, drain endpoint, and WebSocket upgrades.
 */
@RequiredArgsConstructor
public class HttpServer {
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final SocketConfig config;
    private final ISessionManager sessionManager;
    private final IKafkaService kafkaService;
    private final MetricsService metricsService;
    private final PrometheusMetricsExporter metricsExporter;
    private final DrainService drainService;
    private DisposableServer server;

    /**
     * Starts the HTTP server.
     *
     */
    public DisposableServer start() {
        // Create upgrade handler for proper param extraction
        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(
            config, sessionManager, kafkaService, metricsService, drainService
        );

        server = reactor.netty.http.server.HttpServer.create()
            .port(config.getHttpPort())
            .option(ChannelOption.SO_REUSEADDR, true)
            .metrics(true, Function.identity())
            .route(routes -> routes
                // Health check endpoint - fails if draining
                .get("/healthz", (req, res) -> {
                    if (drainService.isDraining()) {
                        return res.status(503).sendString(Mono.just("Draining"));
                    }
                    return res.status(200).sendString(Mono.just("OK"));
                })
                // Readiness check endpoint - separate from liveness
                .get("/readyz", (req, res) -> {
                    if (drainService.isDraining()) {
                        return res.status(503).sendString(Mono.just("Not Ready - Draining"));
                    }
                    return res.status(200).sendString(Mono.just("Ready"));
                })
                // Drain endpoint - called by Kubernetes preStop hook
                .post("/drain", (req, res) -> {
                    log.warn("Drain endpoint called - starting graceful connection draining");
                    return drainService.startDrain()
                        .then(res.status(202).sendString(Mono.just(String.format(
                            "Drain started - %d connections to drain",
                            drainService.getRemainingConnections()
                        ))).then());
                })
                // Drain status endpoint
                .get("/drain/status", (req, res) -> {
                    String status = String.format(
                        "{ \"draining\": %b, \"complete\": %b, \"remaining\": %d }",
                        drainService.isDraining(),
                        drainService.isDrainComplete(),
                        drainService.getRemainingConnections()
                    );
                    return res.status(200)
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just(status));
                })
                // Metrics endpoint with Prometheus scraping
                .get("/metrics", (req, res) ->
                    res.header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                        .sendString(Mono.just(metricsExporter.scrape()))
                )
                // WebSocket upgrade endpoint with param extraction
                .get("/ws/connect", upgradeHandler::handle)
            )
            .bind()
            .doOnNext(port -> log.info("HTTP server started on port {}", port))
            .doOnError(err -> log.error("Failed to start HTTP server", err))
            .block(Duration.ofSeconds(45));

        return server;
    }

    public void stop() {
        server.disposeNow(Duration.ofSeconds(30));
    }
}
