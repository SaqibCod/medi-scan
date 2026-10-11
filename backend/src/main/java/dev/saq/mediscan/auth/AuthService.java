package dev.saq.mediscan.auth;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.report.ReportOwnershipService;
import dev.saq.mediscan.session.Session;
import dev.saq.mediscan.session.SessionRepository;
import dev.saq.mediscan.session.SessionService;
import dev.saq.mediscan.user.UserRole;
import dev.saq.mediscan.user.UserService;
import dev.saq.mediscan.user.UserUpsert;

/**
 * Sign-in, refresh and logout.
 *
 * <p>Sign-in is one transaction over four writes - the user upsert, the report claim, the
 * guest session delete, and the refresh-token insert - so a failure anywhere leaves the guest
 * exactly as they were, still holding a working session and their reports (LLD 7.1). The
 * Google verification happens before it, because it needs no transaction and may call out to
 * Google's key set.
 *
 * <p>Refresh returns a {@link RefreshOutcome} instead of throwing, so the reuse branch's
 * family revocation commits even though the request will fail. The controller maps the outcome
 * to the error.
 */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private final GoogleIdTokenVerifier googleVerifier;
	private final UserService userService;
	private final SessionService sessions;
	private final SessionRepository sessionRepository;
	private final ReportOwnershipService reportOwnership;
	private final RefreshTokenService refreshTokens;
	private final AccessTokenIssuer accessTokens;
	private final int userRetentionDays;

	public AuthService(GoogleIdTokenVerifier googleVerifier, UserService userService,
			SessionService sessions, SessionRepository sessionRepository,
			ReportOwnershipService reportOwnership, RefreshTokenService refreshTokens,
			AccessTokenIssuer accessTokens, MediScanProperties properties) {

		this.googleVerifier = googleVerifier;
		this.userService = userService;
		this.sessions = sessions;
		this.sessionRepository = sessionRepository;
		this.reportOwnership = reportOwnership;
		this.refreshTokens = refreshTokens;
		this.accessTokens = accessTokens;
		this.userRetentionDays = properties.retention().userReportDays();
	}

	/**
	 * Verifies a Google ID token, finds or creates the account, and claims the guest's reports.
	 *
	 * @throws dev.saq.mediscan.config.ApiException {@code 401 GOOGLE_TOKEN_INVALID} if the
	 *     token does not verify. Nothing is written in that case
	 */
	public AuthDtos.GoogleSignInResponse signIn(AuthDtos.GoogleSignInRequest request) {
		// Outside the transaction: this is the one step that can make a network call, and
		// holding a database transaction open across it would be worse for no benefit. It
		// also means a forged token never opens a transaction at all.
		GoogleIdentity identity = googleVerifier.verify(request.idToken());

		return completeSignIn(identity, request.guestSessionToken());
	}

	@Transactional
	AuthDtos.GoogleSignInResponse completeSignIn(GoogleIdentity identity, String guestSessionToken) {
		UserUpsert.Result user = userService.signIn(identity.sub(), identity.displayName());

		int claimed = claimGuestReports(guestSessionToken, user.userId());
		RefreshTokenService.IssuedRefreshToken refresh =
				refreshTokens.issueNewFamily(user.userId());
		AccessTokenIssuer.IssuedAccessToken access =
				accessTokens.issue(user.userId(), user.role());

		// Ids and counts only. Never the Google sub, the display name, or any token (LLD 12).
		log.info("auth sign-in user={} newUser={} claimed={}", user.userId(), user.newUser(),
				claimed);

		return new AuthDtos.GoogleSignInResponse(
				access.token(), access.expiresAt(),
				refresh.token(), refresh.expiresAt(),
				new AuthDtos.UserPayload(user.userId(), user.displayName(), user.role(),
						user.createdAt()),
				claimed, user.newUser());
	}

	/**
	 * Moves the guest's reports, then deletes the session.
	 *
	 * <p>An absent, unknown or expired token is not an error: the client may be signing in
	 * from a tab that never created a session, or from one whose session has aged out. Either
	 * way sign-in succeeds with nothing claimed (contract 2.3).
	 */
	private int claimGuestReports(String guestSessionToken, UUID userId) {
		if (guestSessionToken == null || guestSessionToken.isBlank()) {
			return 0;
		}

		Optional<Session> session = sessions.findActive(guestSessionToken);
		if (session.isEmpty()) {
			return 0;
		}

		UUID sessionId = session.get().getId();
		int claimed = reportOwnership.claimGuestReports(sessionId, userId, userRetentionDays);

		// Strictly after the claim: report cascades from session, so deleting first would
		// take the reports with it (LLD 7.1).
		sessionRepository.deleteById(sessionId);

		return claimed;
	}

	/**
	 * Rotates a refresh token and mints a new access token.
	 *
	 * <p>Deliberately not {@code @Transactional}: {@link RefreshTokenService#rotate} owns the
	 * transaction and has to commit the family revocation on the reuse path before this method
	 * returns the outcome that will become a 401.
	 */
	public Result refresh(AuthDtos.RefreshRequest request) {
		RefreshOutcome outcome = refreshTokens.rotate(request.refreshToken());

		if (!(outcome instanceof RefreshOutcome.Rotated rotated)) {
			return new Result(outcome, null);
		}

		// The user row is loaded again rather than carried through the outcome, so the
		// response's `user` object reflects what is stored right now.
		return userService.find(rotated.userId())
				.map(user -> {
					AccessTokenIssuer.IssuedAccessToken access =
							accessTokens.issue(user.getId(), rotated.role());
					return new Result(outcome, new AuthDtos.TokenResponse(
							access.token(), access.expiresAt(),
							rotated.refreshToken(), rotated.refreshTokenExpiresAt(),
							new AuthDtos.UserPayload(user.getId(), user.getDisplayName(),
									rotated.role(), user.getCreatedAt())));
				})
				.orElseGet(() -> new Result(new RefreshOutcome.Invalid(), null));
	}

	/** Revokes the token's whole family. Always succeeds, by design (contract 2.5). */
	public void logout(AuthDtos.LogoutRequest request) {
		refreshTokens.revokeFamilyOf(request.refreshToken());
	}

	/**
	 * A refresh outcome and, when it succeeded, the body to return.
	 *
	 * @param outcome what happened; the controller switches on this to choose the status
	 * @param body the 200 body, or null for every failing outcome
	 */
	public record Result(RefreshOutcome outcome, AuthDtos.TokenResponse body) {

		/** The role the new access token carries, for tests. Never logged. */
		public UserRole role() {
			return body != null ? body.user().role() : null;
		}
	}
}
