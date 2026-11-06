package dev.saq.mediscan.auth;

import java.time.Instant;
import java.util.UUID;

import dev.saq.mediscan.user.UserRole;

/**
 * What happened when a refresh token was presented.
 *
 * <p>A result type rather than exceptions, and that is a correctness requirement, not a style
 * preference. The reuse branch <em>revokes the whole family</em> and then fails the request;
 * if it signalled that by throwing out of the transactional method, Spring would roll the
 * transaction back and the revocation - the entire security response to a stolen token -
 * would be undone. Returning a value lets the transaction commit, and the controller turns
 * the result into the 401 (LLD 7.2).
 *
 * <p>Sealed so the controller's switch is exhaustive: a new outcome cannot be added without
 * every caller being made to decide what it means.
 */
public sealed interface RefreshOutcome {

	/**
	 * The token was live and has been swapped for a new one.
	 *
	 * @param userId whose token this is
	 * @param role the role recomputed from config during the refresh, which may differ from
	 *     the one the old access token carried
	 * @param refreshToken the new raw token. Returned to the client once and never stored
	 * @param refreshTokenExpiresAt when the new token expires, capped by the family's limit
	 */
	record Rotated(UUID userId, UserRole role, String refreshToken, Instant refreshTokenExpiresAt)
			implements RefreshOutcome {

		/** Never prints {@link #refreshToken()} (CLAUDE.md rule 14). */
		@Override
		public String toString() {
			return "Rotated[userId=" + userId + ", role=" + role + ", refreshTokenExpiresAt="
					+ refreshTokenExpiresAt + ", refreshToken=***]";
		}
	}

	/**
	 * Unknown, expired, or past its family's maximum age.
	 *
	 * <p>One outcome for all three: the client's move is the same - sign in again - and the
	 * API does not say which, so a stolen token teaches nobody anything.
	 */
	record Invalid() implements RefreshOutcome {
	}

	/**
	 * An already-revoked token was presented again, so someone has a copy.
	 *
	 * <p>The family has been revoked by the time this is returned.
	 */
	record ReuseDetected() implements RefreshOutcome {
	}
}
