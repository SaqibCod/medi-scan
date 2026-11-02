package dev.saq.mediscan.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Issues and resolves guest session tokens.
 *
 * <p>Per contract section 2.2 and {@code CLAUDE.md} rule 14: the token is 32 random bytes,
 * returned to the client once, and only its SHA-256 hash is ever stored. Nothing here logs a
 * raw token - session ids are logged instead, which are safe because they are not credentials.
 */
@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private final SessionRepository repository;
	private final Duration ttl;
	private final Clock clock;

	/**
	 * @param clock the application clock, not {@code Instant.now()}. A session's {@code
	 *     expires_at} is written here and read by {@code RetentionJob}, which has always taken
	 *     its time from this bean; writing from the system clock while the sweep reads from the
	 *     injected one means the two disagree about when "now" is, and the sweep then deletes
	 *     live sessions. Harmless in production, where they are the same instant - but it is the
	 *     same defect that deleted the evaluation's own data mid-run
	 *     ({@code backend/CLAUDE.md}, "Time")
	 */
	public SessionService(SessionRepository repository, MediScanProperties properties,
			Clock clock) {

		this.repository = repository;
		this.ttl = Duration.ofHours(properties.session().ttlHours());
		this.clock = clock;
	}

	/**
	 * Creates a session and returns its raw token. The token cannot be recovered afterwards.
	 */
	@Transactional
	public SessionResponse create() {
		String token = SecureTokens.newToken();

		Instant now = Instant.now(clock);
		Instant expiresAt = now.plus(ttl);

		Session session = new Session(UUID.randomUUID(), SecureTokens.hash(token), now, expiresAt);
		repository.save(session);

		log.debug("Created session {} expiring at {}", session.getId(), expiresAt);
		return new SessionResponse(token, expiresAt);
	}

	/**
	 * Resolves a raw token to its session, or empty when the token is unknown or expired.
	 *
	 * <p>Both cases return empty on purpose: the caller turns either into the same
	 * {@code 401 SESSION_INVALID}, so the API does not distinguish "never existed" from
	 * "expired".
	 */
	@Transactional(readOnly = true)
	public Optional<Session> findActive(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		return repository.findActiveByTokenHash(SecureTokens.hash(token), Instant.now(clock));
	}
}
