package com.qqsuccubus.socket.session;

import com.qqsuccubus.core.msg.Envelope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface for session management (Dependency Inversion Principle).
 * <p>
 * Abstracts session lifecycle, message delivery, and resume logic.
 * </p>
 */
public interface ISessionManager {
    /**
     * Creates a new session for a user.
     *
     * @param clientId      User identifier
     * @param resumeOffset Optional resume offset (null for new connection)
     * @return Mono of Session
     */
    Mono<Session> createSession(String clientId, int resumeOffset);

    Mono<String> getClientTargetNodeId(String clientId);

    /**
     * Removes a session for a user.
     *
     * @param userId User identifier
     * @return Mono completing when removed
     */
    Mono<Void> removeSession(String userId);

    /**
     * Delivers a message to a user's session.
     *
     * @param envelope Message envelope
     * @return Mono<Boolean> true if delivered locally, false if needs relay
     */
    Mono<Boolean> deliverMessage(Envelope envelope);

    /**
     * Retrieves buffered messages for resume.
     *
     * @param userId       User identifier
     * @param resumeOffset min envelope offset
     * @return Flux of Envelope
     */
    Flux<Envelope> getBufferedMessages(String userId, int resumeOffset);

    /**
     * Drains all active sessions (graceful shutdown).
     *
     * @return Mono completing when all sessions drained
     */
    Mono<Void> drainAll();

}







