package com.qqsuccubus.socket.session;

import com.qqsuccubus.core.msg.Envelope;
import com.qqsuccubus.core.util.JsonUtils;
import com.qqsuccubus.socket.config.SocketConfig;
import com.qqsuccubus.socket.metrics.MetricsService;
import com.qqsuccubus.socket.redis.IRedisService;
import io.netty.util.internal.PlatformDependent;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;

/**
 * Manages active WebSocket sessions and message delivery (refactored for SOLID).
 * <p>
 * Responsibilities (Single Responsibility Principle):
 * - Track active sessions
 * - Coordinate message delivery
 * - Manage session lifecycle
 * </p>
 * <p>
 * Delegates to:
 * - SessionFactory: Session creation logic
 * - MessageBufferService: Message buffering/replay
 * </p>
 */
public class SessionManager implements ISessionManager {
	private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

	private final IRedisService redisService;
	private final SocketConfig config;
	private final MetricsService metricsService;
	private final SessionFactory sessionFactory;
	@Getter
	private final MessageBufferService bufferService;

	// Active sessions: clientId -> Session
	private final Map<String, Session> activeSessions = PlatformDependent.newConcurrentHashMap();

	public SessionManager(IRedisService redisService, SocketConfig config, MetricsService metricsService) {
		this.redisService = redisService;
		this.config = config;
		this.metricsService = metricsService;
		this.sessionFactory = new SessionFactory(config);
		this.bufferService = new MessageBufferService(redisService, config.getBufferMax());
	}

	@Override
	public Mono<Session> createSession(String clientId, int resumeOffset) {
		Session session = sessionFactory.createSession(clientId, resumeOffset);

		if (resumeOffset > 0) {
			metricsService.recordResumeSuccess();
			log.info("Resume verified for user {} at offset {}", clientId, resumeOffset);
		}

		// Add to local session map BEFORE Redis save
		activeSessions.put(clientId, session);
		log.info("Session added to local map for clientId='{}', map size now={}", clientId, activeSessions.size());

		// Save to Redis
		return redisService.saveSession(clientId, config.getNodeId(), resumeOffset)
				.doOnSuccess(v -> {
					log.info("Session persisted to Redis for user {}, offset={}, nodeId={}",
							clientId, resumeOffset, config.getNodeId());
				})
				.doOnError(err -> {
					// If Redis save fails, remove from local map to prevent inconsistency
					log.error("Failed to save session to Redis for {}, removing from local map", clientId, err);
					activeSessions.remove(clientId);
				})
				.thenReturn(session);
	}

	@Override
	public Mono<Void> removeSession(String clientId) {
		Session session = activeSessions.remove(clientId);
		if (session == null) {
			return Mono.empty();
		}

		Flux<Mono<Void>> writePendingMessagesToBuffer = session.getOutboundFlux()
				.map(JsonUtils::writeValueAsString)
				.map(msg -> redisService.appendToBuffer(clientId, msg));

		return Mono.when(
						writePendingMessagesToBuffer,
						redisService.deleteSession(clientId)
				)
				.then(Mono.just(session.getSink().tryEmitComplete()))
				.then()
				.doOnSuccess(v -> {
					log.info("Session removed for user {}", clientId);
				});
	}

	@Override
	public Mono<Boolean> deliverMessage(Envelope envelope) {
		return Mono.defer(() -> {
            metricsService.recordDeliverLocal();
			String clientId = envelope.getToClientId();

			log.debug("Checking activeSessions map: total size={}, contains key '{}'={}",
					activeSessions.size(), clientId, activeSessions.containsKey(clientId));

			Session session = activeSessions.get(clientId);

			log.info("Attempting to deliver message to clientId='{}', session found={}, active sessions count={}, active session keys={}",
					clientId, (session != null), activeSessions.size(), activeSessions.keySet());

			if (session == null) {
				// User not on this node; caller should relay
				log.info("Session not found for clientId='{}', returning false", clientId);
				return Mono.just(false);
			}

			log.info("Found session for clientId='{}', attempting to emit message msgId={}",
					clientId, envelope.getMsgId());

			// Emit to outbound sink
			Sinks.EmitResult result = session.getSink().tryEmitNext(envelope);
			if (result.isFailure()) {
				log.warn("Failed to emit message to user {}: {}", clientId, result);
				if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
					metricsService.recordDropBufferFull();
				}
				// Buffer to Redis for resume
				return bufferService.bufferMessage(envelope).then(Mono.just(false));
			}

			log.info("Successfully emitted message to clientId='{}', msgId={}", clientId, envelope.getMsgId());
			return Mono.just(true);
		});
	}

	@Override
	public Flux<Envelope> getBufferedMessages(String clientId, int resumeOffset) {
		// Redis Streams maintain message order automatically
		// The stream ID serves as the offset, not the envelope offset field
		// For now, return all buffered messages since Redis handles TTL and order
		return bufferService.getBufferedMessages(clientId)
				.doOnNext(envelope -> {
					// Messages from buffer need offsets assigned for client tracking
					if (envelope.getOffset() < 0) {
						// Will be set when emitted through outbound flux
						log.debug("Buffered message {} for client {} will get offset assigned",
								envelope.getMsgId(), clientId);
					}
				});
	}

	@Override
	public Mono<Void> drainAll() {
		log.info("Draining {} active sessions", activeSessions.size());
		return Flux.fromIterable(activeSessions.keySet())
				.flatMap(this::removeSession)
				.then();
	}

}

