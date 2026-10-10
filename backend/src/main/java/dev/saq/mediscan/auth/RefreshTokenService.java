package dev.saq.mediscan.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.auth.RefreshTokenRepository.RefreshTokenRow;
import dev.saq.mediscan.session.SecureTokens;
import dev.saq.mediscan.user.UserEntity;
import dev.saq.mediscan.user.UserRole;
import dev.saq.mediscan.user.UserService;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>A token belongs to a family: one sign-in creates one family, and every refresh replaces
 * one member of it with another. Two rules follow from that, and both exist because the token
 * lives in the browser where a script-injection bug could read it (docs/plan.md section 4.9):
 *
 * <ul>
 * <li><strong>Rotation.</strong> Using a token revokes it. A copy taken earlier is therefore
 * worthless as soon as the real client refreshes once.</li>
 * <li><strong>Reuse detection.</strong> Presenting an already-revoked token means two parties
 * hold it, and there is no way to tell which one is the user. The whole family is revoked and
 * both have to sign in again.</li>
 * </ul>
 *
 * <p>A family also cannot outlive {@code REFRESH_FAMILY_MAX_DAYS} from the original sign-in,
 * however often it is refreshed, so an indefinitely-refreshed session still ends.
 */
@Service
public class RefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	private final RefreshTokenRepository tokens;
	private final UserService users;
	private final Clock clock;
	private final Duration ttl;
	private final Duration familyMaxAge;

	public RefreshTokenService(RefreshTokenRepository tokens, UserService users,
			AuthProperties properties, Clock clock) {

		this.tokens = tokens;
		this.users = users;
		this.clock = clock;
		this.ttl = properties.refresh().ttl();
		this.familyMaxAge = properties.refresh().familyMaxAge();
	}

	/**
	 * Starts a new family for a fresh sign-in.
	 *
	 * <p>Not transactional on its own: sign-in wraps the user upsert, the report claim and
	 * this in one transaction, so a failure anywhere leaves the guest exactly as they were
	 * (LLD 7.1).
	 */
	public IssuedRefreshToken issueNewFamily(UUID userId) {
		Instant now = Instant.now(clock);
		return issue(userId, UUID.randomUUID(), now, now);
	}

	/**
	 * Swaps a live token for a new one in the same family.
	 *
	 * <p>Transactional and returning a result rather than throwing, because the reuse branch
	 * has to commit its revocation even though the request fails (see {@link RefreshOutcome}).
	 *
	 * <p>The role is recomputed from {@code ADMIN_GOOGLE_SUBS} here and written back when it
	 * has changed, which is what makes an admin change apply within one access token lifetime
	 * rather than needing the user to sign in again (docs/dataflow.md section 3.2).
	 */
	@Transactional
	public RefreshOutcome rotate(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return new RefreshOutcome.Invalid();
		}

		Instant now = Instant.now(clock);
		Optional<RefreshTokenRow> found = tokens.findForUpdate(SecureTokens.hash(rawToken));
		if (found.isEmpty()) {
			return new RefreshOutcome.Invalid();
		}

		RefreshTokenRow row = found.get();

		if (row.isRevoked()) {
			// Someone is replaying a token the real client already exchanged. There is no way
			// to tell the thief from the user, so neither keeps the session.
			int revoked = tokens.revokeFamily(row.familyId(), now);
			// WARN, with ids only: this is a security event worth noticing, and neither id is
			// a credential (LLD 12).
			log.warn("auth reuse-detected family={} user={} revoked={}", row.familyId(),
					row.userId(), revoked);
			return new RefreshOutcome.ReuseDetected();
		}

		if (!row.expiresAt().isAfter(now)) {
			return new RefreshOutcome.Invalid();
		}

		// The family's own deadline caps the new token: refreshing cannot extend a session
		// beyond REFRESH_FAMILY_MAX_DAYS from the original sign-in.
		Instant newExpiry = earlier(now.plus(ttl), row.familyStartedAt().plus(familyMaxAge));
		if (!newExpiry.isAfter(now)) {
			return new RefreshOutcome.Invalid();
		}

		UUID newId = UUID.randomUUID();
		if (tokens.revokeIfLive(row.id(), newId, now) != 1) {
			// Lost a race with a concurrent rotation of the same token. FOR UPDATE normally
			// prevents this; treating it as reuse is the safe reading if it ever happens.
			tokens.revokeFamily(row.familyId(), now);
			log.warn("auth reuse-detected family={} user={} reason=lost-race", row.familyId(),
					row.userId());
			return new RefreshOutcome.ReuseDetected();
		}

		Optional<UserEntity> user = users.find(row.userId());
		if (user.isEmpty()) {
			// The account was deleted while this token was live. The cascade should have taken
			// the token with it, so this is belt and braces.
			return new RefreshOutcome.Invalid();
		}

		UserRole role = users.currentRole(user.get());
		users.updateRoleIfChanged(user.get(), role);

		String rawNewToken = SecureTokens.newToken();
		tokens.insert(newId, row.userId(), row.familyId(), SecureTokens.hash(rawNewToken), now,
				newExpiry, row.familyStartedAt());

		log.debug("auth refresh rotated user={} family={}", row.userId(), row.familyId());
		return new RefreshOutcome.Rotated(row.userId(), role, rawNewToken, newExpiry);
	}

	/**
	 * Revokes the family a token belongs to.
	 *
	 * <p>Logout always succeeds from the client's point of view, so an unknown token is not an
	 * error - there is nothing to revoke and nothing the client should do differently
	 * (contract section 2.5).
	 */
	@Transactional
	public void revokeFamilyOf(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return;
		}

		Instant now = Instant.now(clock);
		tokens.findForUpdate(SecureTokens.hash(rawToken)).ifPresent(row -> {
			int revoked = tokens.revokeFamily(row.familyId(), now);
			log.info("auth logout family={} revoked={}", row.familyId(), revoked);
		});
	}

	private IssuedRefreshToken issue(UUID userId, UUID familyId, Instant now,
			Instant familyStartedAt) {

		Instant expiresAt = earlier(now.plus(ttl), familyStartedAt.plus(familyMaxAge));
		String rawToken = SecureTokens.newToken();

		tokens.insert(UUID.randomUUID(), userId, familyId, SecureTokens.hash(rawToken), now,
				expiresAt, familyStartedAt);

		return new IssuedRefreshToken(rawToken, expiresAt);
	}

	private static Instant earlier(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}

	/**
	 * A freshly issued refresh token and when it expires.
	 *
	 * @param token the raw token. Returned to the client once; only its hash is stored
	 */
	public record IssuedRefreshToken(String token, Instant expiresAt) {

		/** Never prints {@link #token()} (CLAUDE.md rule 14). */
		@Override
		public String toString() {
			return "IssuedRefreshToken[expiresAt=" + expiresAt + ", token=***]";
		}
	}
}
