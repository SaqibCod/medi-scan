package medi_scan.backend.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import medi_scan.backend.config.MediScanProperties;

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

	/** 32 bytes, which is 43 characters once base64url encoded without padding. */
	private static final int TOKEN_BYTES = 32;

	private final SessionRepository repository;
	private final Duration ttl;
	private final SecureRandom random = new SecureRandom();
	private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

	public SessionService(SessionRepository repository, MediScanProperties properties) {
		this.repository = repository;
		this.ttl = Duration.ofHours(properties.session().ttlHours());
	}

	/**
	 * Creates a session and returns its raw token. The token cannot be recovered afterwards.
	 */
	@Transactional
	public SessionResponse create() {
		byte[] tokenBytes = new byte[TOKEN_BYTES];
		random.nextBytes(tokenBytes);
		String token = encoder.encodeToString(tokenBytes);

		Instant now = Instant.now();
		Instant expiresAt = now.plus(ttl);

		Session session = new Session(UUID.randomUUID(), hash(token), now, expiresAt);
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
		return repository.findActiveByTokenHash(hash(token), Instant.now());
	}

	/**
	 * SHA-256, hex encoded.
	 *
	 * <p>A plain hash rather than a password hash on purpose: the token is 32 bytes of
	 * {@link SecureRandom} output, so there is no dictionary to attack and nothing for a slow
	 * KDF to protect. Hashing is only here so a leaked database cannot be replayed.
	 */
	private String hash(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is required of every JVM, so this cannot happen.
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}
}
