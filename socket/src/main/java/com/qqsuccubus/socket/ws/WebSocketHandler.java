package com.qqsuccubus.socket.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.qqsuccubus.core.msg.Envelope;
import com.qqsuccubus.core.util.BytesUtils;
import com.qqsuccubus.core.util.JsonUtils;
import com.qqsuccubus.socket.config.SocketConfig;
import com.qqsuccubus.socket.kafka.IKafkaService;
import com.qqsuccubus.socket.metrics.MetricsService;
import com.qqsuccubus.socket.session.ISessionManager;
import com.qqsuccubus.socket.session.Session;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

import java.util.Map;

/**
 * WebSocket handler for user connections.
 * <p>
 * Protocol (server → client):
 * <ul>
 *   <li>welcome: {userId, nodeId, ringVersion, resumeToken}</li>
 *   <li>message: Envelope</li>
 *   <li>drain: {deadline, reason}</li>
 * </ul>
 * </p>
 * <p>
 * Protocol (client → server):
 * <ul>
 *   <li>ack: {msgId}</li>
 *   <li>ping: {}</li>
 *   <li>message: Envelope (upstream)</li>
 * </ul>
 * </p>
 */
public class WebSocketHandler {
	private static final Logger log = LoggerFactory.getLogger(WebSocketHandler.class);

	private final SocketConfig config;
	private final ISessionManager sessionManager;
	private final IKafkaService kafkaService;
	private final MetricsService metricsService;

	public WebSocketHandler(
			SocketConfig config,
			ISessionManager sessionManager,
			IKafkaService kafkaService,
			MetricsService metricsService
	) {
		this.config = config;
		this.sessionManager = sessionManager;
		this.kafkaService = kafkaService;
		this.metricsService = metricsService;
	}

	/**
	 * Handles WebSocket connection lifecycle (with pre-extracted parameters).
	 *
	 * @param inbound      WebSocket inbound
	 * @param outbound     WebSocket outbound
	 * @param clientId     User identifier (extracted from query params)
	 * @param resumeOffset Optional resume offset (extracted from query params)
	 * @return Publisher for the connection
	 */
	public Publisher<Void> handle(WebsocketInbound inbound, WebsocketOutbound outbound,
								  String clientId, int resumeOffset) {

		MDC.put("clientId", clientId);
		log.debug("WebSocket handshake for client {} (resume={})", clientId, resumeOffset);
		// Create session
		return sessionManager.createSession(clientId, resumeOffset)
				.flatMap(session -> {
					handleConnectionStateUpdates(inbound, outbound, clientId);

					// Combined: send outbound and receive inbound in parallel
					return Mono.when(
							outbound.sendString(sendOutboundMessages(clientId, resumeOffset, session)),
							handleInboundMessages(inbound, clientId)
					);
				})
				.onErrorResume(err -> {
					log.error("WebSocket error for clientId {}", clientId, err);
					return outbound.sendClose();
				});
	}

	private void handleConnectionStateUpdates(WebsocketInbound inbound, WebsocketOutbound outbound, String clientId) {
		inbound.withConnection(connection -> {
			long idleTimeoutInMillis = config.getIdleTimeout() * 1000L;
			long pingTimeoutInMillis = config.getPingInterval() * 1000L;

			connection.onWriteIdle(pingTimeoutInMillis, () -> connection.outbound().sendObject(
							Mono.just(new PingWebSocketFrame())
					))
					.onReadIdle(idleTimeoutInMillis, outbound::sendClose)
					.onDispose(() -> {
						log.debug("WebSocket connection disposed for client {}, removing session", clientId);
						sessionManager.removeSession(clientId).subscribe();
					});
		});
	}

	private Flux<String> sendOutboundMessages(String clientId, int resumeOffset, Session session) {
		return Flux.concat(
				Flux.just(toWelcomeMessage(clientId)),
				sessionManager.getBufferedMessages(clientId, resumeOffset).map(JsonUtils::writeValueAsString),
				session.getOutboundFlux().map(JsonUtils::writeValueAsString)
		).doOnNext(message -> {
			// Record outbound WebSocket traffic
			metricsService.recordNetworkOutboundWs(BytesUtils.getBytesLength(message));
		});
	}

	private Mono<Void> handleInboundMessages(WebsocketInbound inbound, String clientId) {
		// Inbound: handle client messages
		return inbound.aggregateFrames()
				.receive()
				.asString()
				.onBackpressureBuffer(config.getPerConnBufferSize())
				.concatMap(msg -> handleInboundMessage(clientId, msg).onErrorResume(err -> {
							log.warn("Error processing message from {}: {}", clientId, err.getMessage());
							return Mono.empty();
						})
				)
				.doOnError(err -> {
					// Only log non-AbortedException errors (AbortedException is expected on close)
					if (!(err instanceof reactor.netty.channel.AbortedException)) {
						log.error("Fatal error in inbound stream for {}", clientId, err);
					}
				})
				.onErrorResume(err -> Mono.empty())
				.then();
	}

	private String toWelcomeMessage(String clientId) {
		return JsonUtils.writeValueAsString(Map.of(
				"nodeId", config.getNodeId(), "clientId", clientId)
		);
	}

	private Mono<Void> handleInboundMessage(String userId, String messageJson) {
		try {
			log.debug("Processing message from {}: {}", userId, messageJson);

			// Record inbound WebSocket traffic
			metricsService.recordNetworkInboundWs(BytesUtils.getBytesLength(messageJson));

			Envelope msg = JsonUtils.readValue(messageJson, new TypeReference<>() {
			});
            msg.setTs(System.currentTimeMillis());

			if (msg.getType() == null) {
				log.warn("Received null or incomplete message from {}: {}", userId, messageJson);
				return Mono.empty();
			}

			String type = msg.getType();

			switch (type) {
				case "ack" -> {
					// Client acknowledged a message
					String msgId = msg.getMsgId();
					log.debug("User {} acked msgId {}", userId, msgId);
					return Mono.empty();
				}
				case "ping" -> {
					// Keepalive ping
					return Mono.empty();
					// Keepalive ping
				}
				case "message" -> {
					return sendMessage(msg);
				}
				default -> log.warn("Unknown message type '{}' from {}", type, userId);
			}

			return Mono.empty();
		} catch (Exception e) {
			log.error("Failed to handle inbound message from {}: message='{}', error={}",
					userId, messageJson, e.getMessage(), e);
			return Mono.empty();
		}
	}

	private Mono<Void> sendMessage(Envelope envelope) {
		log.debug("Attempting to deliver message from {} to {}", envelope.getFrom(), envelope.getToClientId());

		// Try local delivery first
		return sessionManager.deliverMessage(envelope)
				.flatMap(delivered -> {
					if (delivered) {
						log.debug("Message delivered locally from {} to {}", envelope.getFrom(), envelope.getToClientId());
						// Record latency for local delivery
						metricsService.recordLatencyMs(envelope.getTs());
						return Mono.empty();
					}
					log.debug("Message to {} not delivered locally, resolving target node", envelope.getToClientId());
					return kafkaService.publishRelay(null, envelope)
							.doOnSuccess(v -> {
								// Record latency for relay delivery (includes Kafka publish time)
								metricsService.recordLatencyMs(envelope.getTs());
							})
							.doOnError(err -> log.error("Failed to relay message from {} to {}", envelope.getFrom(),
									envelope.getToClientId(), err));
				})
				.onErrorResume(err -> {
					log.error("Error delivering message from {} to {}: {}",
							envelope.getFrom(), envelope.getToClientId(), err.getMessage()
					);

					return Mono.empty();
				});
	}
}

