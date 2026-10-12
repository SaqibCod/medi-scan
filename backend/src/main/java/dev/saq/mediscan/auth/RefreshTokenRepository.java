package dev.saq.mediscan.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.SqlTime;

/**
 * {@code refresh_token} persistence.
 *
 * <p>All {@link JdbcClient}, no JPA, because every statement here is either a conditional
 * update or a {@code SELECT ... FOR UPDATE} - the things {@code backend/CLAUDE.md} says to
 * write as SQL. An ORM would make the locking and the "update only if still live" conditions
 * invisible, and those conditions are the whole mechanism.
 *
 * <p>There is no entity for this table, so Hibernate never maps it and never sees a raw token.
 */
@Component
public class RefreshTokenRepository {

	private final JdbcClient jdbc;

	public RefreshTokenRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * One stored token, locked for the rest of the transaction.
	 *
	 * <p>{@code FOR UPDATE} is what serialises two concurrent refreshes of the same token:
	 * the second one waits, then sees the {@code revoked_at} the first one set and is treated
	 * as reuse. Without the lock both could read "live" and both would succeed, which would
	 * leave two valid tokens in one family and make reuse detection meaningless.
	 */
	public Optional<RefreshTokenRow> findForUpdate(String tokenHash) {
		return jdbc.sql("""
				SELECT id, user_id, family_id, issued_at, expires_at, family_started_at, revoked_at
				  FROM refresh_token
				 WHERE token_hash = :hash
				   FOR UPDATE
				""")
				.param("hash", tokenHash)
				.query((rs, rowNum) -> new RefreshTokenRow(
						rs.getObject("id", UUID.class),
						rs.getObject("user_id", UUID.class),
						rs.getObject("family_id", UUID.class),
						rs.getTimestamp("issued_at").toInstant(),
						rs.getTimestamp("expires_at").toInstant(),
						rs.getTimestamp("family_started_at").toInstant(),
						rs.getTimestamp("revoked_at") == null
								? null
								: rs.getTimestamp("revoked_at").toInstant()))
				.optional();
	}

	/** Stores a token's hash. The raw token exists only in the response. */
	public void insert(UUID id, UUID userId, UUID familyId, String tokenHash, Instant issuedAt,
			Instant expiresAt, Instant familyStartedAt) {

		jdbc.sql("""
				INSERT INTO refresh_token
				    (id, user_id, family_id, token_hash, issued_at, expires_at, family_started_at)
				VALUES (:id, :userId, :familyId, :hash, :issuedAt, :expiresAt, :familyStartedAt)
				""")
				.param("id", id)
				.param("userId", userId)
				.param("familyId", familyId)
				.param("hash", tokenHash)
				.param("issuedAt", SqlTime.utc(issuedAt))
				.param("expiresAt", SqlTime.utc(expiresAt))
				.param("familyStartedAt", SqlTime.utc(familyStartedAt))
				.update();
	}

	/**
	 * Revokes one token, only if it is still live.
	 *
	 * <p>The {@code revoked_at IS NULL} condition is the atomic swap: exactly one of two
	 * racing rotations can update the row, and the loser gets 0 back and knows it lost
	 * (docs/dataflow.md section 3.4).
	 *
	 * @return 1 when this call revoked the token, 0 when someone else already had
	 */
	public int revokeIfLive(UUID id, UUID replacedBy, Instant now) {
		return jdbc.sql("""
				UPDATE refresh_token
				   SET revoked_at = :now, replaced_by = :replacedBy
				 WHERE id = :id AND revoked_at IS NULL
				""")
				.param("id", id)
				.param("replacedBy", replacedBy)
				.param("now", SqlTime.utc(now))
				.update();
	}

	/**
	 * Revokes every live token in a family.
	 *
	 * <p>Used on logout and on reuse detection. Already-revoked rows are left alone so the
	 * original revocation time survives for anyone reading the table afterwards.
	 *
	 * @return how many tokens were revoked
	 */
	public int revokeFamily(UUID familyId, Instant now) {
		return jdbc.sql("""
				UPDATE refresh_token
				   SET revoked_at = :now
				 WHERE family_id = :familyId AND revoked_at IS NULL
				""")
				.param("familyId", familyId)
				.param("now", SqlTime.utc(now))
				.update();
	}

	/**
	 * Removes tokens that can no longer be used, and revoked ones once they stop being useful.
	 *
	 * <p>A revoked token is kept for a day rather than deleted at once, because it is what
	 * makes reuse detectable: delete it immediately and a stolen copy would come back as
	 * "unknown token" instead of "someone is replaying this", and the family would not be
	 * revoked (docs/dataflow.md section 9.2).
	 *
	 * @return how many rows were removed
	 */
	public int deleteExpiredAndStaleRevoked(Instant now, Instant revokedBefore) {
		return jdbc.sql("""
				DELETE FROM refresh_token
				 WHERE expires_at < :now
				    OR (revoked_at IS NOT NULL AND revoked_at < :revokedBefore)
				""")
				.param("now", SqlTime.utc(now))
				.param("revokedBefore", SqlTime.utc(revokedBefore))
				.update();
	}

	/** How many live tokens a family has. For tests and nothing else. */
	public int countLiveInFamily(UUID familyId) {
		Integer count = jdbc.sql("""
				SELECT count(*) FROM refresh_token
				 WHERE family_id = :familyId AND revoked_at IS NULL
				""")
				.param("familyId", familyId)
				.query(Integer.class)
				.single();
		return count != null ? count : 0;
	}

	/**
	 * A stored refresh token, without its hash.
	 *
	 * <p>The hash is deliberately not a field: nothing downstream needs it, and leaving it out
	 * means no caller can accidentally log or compare it.
	 */
	public record RefreshTokenRow(UUID id, UUID userId, UUID familyId, Instant issuedAt,
			Instant expiresAt, Instant familyStartedAt, Instant revokedAt) {

		public boolean isRevoked() {
			return revokedAt != null;
		}
	}
}
