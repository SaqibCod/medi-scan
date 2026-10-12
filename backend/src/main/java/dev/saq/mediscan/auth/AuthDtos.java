package dev.saq.mediscan.auth;

import java.time.Instant;
import java.util.UUID;

import dev.saq.mediscan.user.UserRole;

import jakarta.validation.constraints.NotBlank;

/**
 * Request and response shapes for {@code /api/auth/*}, exactly as contract sections 2.3 to 2.5
 * define them.
 *
 * <p>Grouped in one file because they are one conversation and are never used apart. Every
 * type that carries a token overrides {@code toString}: these records are the ones most likely
 * to end up in a log line or an exception message, and a record's generated {@code toString}
 * prints every field (CLAUDE.md rule 14, LLD 12).
 */
public final class AuthDtos {

	private AuthDtos() {
	}

	/**
	 * {@code POST /api/auth/google}.
	 *
	 * @param idToken the Google ID token, the only credential this endpoint accepts
	 * @param guestSessionToken optional. When present and still valid, that session's reports
	 *     move to the account. An invalid or expired one is not an error - sign-in succeeds
	 *     with {@code claimedReportCount: 0} (contract 2.3)
	 */
	public record GoogleSignInRequest(@NotBlank String idToken, String guestSessionToken) {

		@Override
		public String toString() {
			return "GoogleSignInRequest[idToken=***, guestSessionToken="
					+ (guestSessionToken == null ? "null" : "***") + "]";
		}
	}

	/** {@code POST /api/auth/refresh}. */
	public record RefreshRequest(@NotBlank String refreshToken) {

		@Override
		public String toString() {
			return "RefreshRequest[refreshToken=***]";
		}
	}

	/** {@code POST /api/auth/logout}. */
	public record LogoutRequest(@NotBlank String refreshToken) {

		@Override
		public String toString() {
			return "LogoutRequest[refreshToken=***]";
		}
	}

	/**
	 * The {@code user} object shared by sign-in, refresh and {@code GET /api/me}.
	 *
	 * <p>No email and no photo, because neither is stored (CLAUDE.md rule 13).
	 */
	public record UserPayload(UUID id, String displayName, UserRole role, Instant createdAt) {

		/** Never prints {@link #displayName()}: it is a real person's name (LLD 12). */
		@Override
		public String toString() {
			return "UserPayload[id=" + id + ", role=" + role + ", createdAt=" + createdAt
					+ ", displayName=***]";
		}
	}

	/** {@code POST /api/auth/refresh}'s 200 body (contract 2.4). */
	public record TokenResponse(
			String accessToken,
			Instant accessTokenExpiresAt,
			String refreshToken,
			Instant refreshTokenExpiresAt,
			UserPayload user) {

		@Override
		public String toString() {
			return "TokenResponse[accessTokenExpiresAt=" + accessTokenExpiresAt
					+ ", refreshTokenExpiresAt=" + refreshTokenExpiresAt
					+ ", accessToken=***, refreshToken=***]";
		}
	}

	/**
	 * {@code POST /api/auth/google}'s 200 body (contract 2.3).
	 *
	 * <p>The same fields as {@link TokenResponse} plus two, repeated rather than inherited
	 * because records cannot extend. The contract describes it the same way round.
	 *
	 * @param claimedReportCount reports moved from the guest session; 0 when none
	 * @param newUser true on a first sign-in, so the UI can welcome them
	 */
	public record GoogleSignInResponse(
			String accessToken,
			Instant accessTokenExpiresAt,
			String refreshToken,
			Instant refreshTokenExpiresAt,
			UserPayload user,
			int claimedReportCount,
			boolean newUser) {

		@Override
		public String toString() {
			return "GoogleSignInResponse[accessTokenExpiresAt=" + accessTokenExpiresAt
					+ ", refreshTokenExpiresAt=" + refreshTokenExpiresAt
					+ ", claimedReportCount=" + claimedReportCount + ", newUser=" + newUser
					+ ", accessToken=***, refreshToken=***]";
		}
	}
}
