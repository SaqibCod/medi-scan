package dev.saq.mediscan.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.ErrorCode;

import jakarta.validation.Valid;

/**
 * The three {@code /api/auth/*} endpoints (contract sections 2.3 to 2.5).
 *
 * <p>All three are public: the credential <em>is</em> the request body, so requiring one in a
 * header would be circular. They are rate limited per IP instead
 * ({@link AuthRateLimitFilter}).
 *
 * <p>Thin, as every controller here is. The one piece of logic that does live in this class is
 * turning a {@link RefreshOutcome} into a status code, and it is here on purpose: the service
 * must not throw on the reuse path, because that would roll back the family revocation it just
 * made (LLD 7.2).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService auth;

	public AuthController(AuthService auth) {
		this.auth = auth;
	}

	/**
	 * {@code POST /api/auth/google} - exchange a Google ID token for this API's own tokens.
	 *
	 * <p>200 rather than 201: signing in a second time creates nothing new from the client's
	 * point of view, and the contract documents 200.
	 */
	@PostMapping("/google")
	public AuthDtos.GoogleSignInResponse google(
			@Valid @RequestBody AuthDtos.GoogleSignInRequest request) {

		return auth.signIn(request);
	}

	/**
	 * {@code POST /api/auth/refresh} - rotate the refresh token and mint a new access token.
	 *
	 * <p>Both failing outcomes answer {@code 401 REFRESH_TOKEN_INVALID}, and the client's move
	 * is the same for both: clear the tokens and sign in again. The API does not say whether
	 * the token was unknown, expired or replayed - telling a caller that a token was "already
	 * used" would confirm it was real.
	 */
	@PostMapping("/refresh")
	public AuthDtos.TokenResponse refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
		AuthService.Result result = auth.refresh(request);

		return switch (result.outcome()) {
			case RefreshOutcome.Rotated ignored -> result.body();
			case RefreshOutcome.Invalid ignored -> throw refreshTokenInvalid();
			case RefreshOutcome.ReuseDetected ignored -> throw refreshTokenInvalid();
		};
	}

	/**
	 * {@code POST /api/auth/logout} - revoke the token's family.
	 *
	 * <p>204 even for an unknown or already-revoked token, so logout always succeeds from the
	 * client's point of view. A client that could not log out would be stuck showing a signed-in
	 * header it cannot clear.
	 */
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody AuthDtos.LogoutRequest request) {
		auth.logout(request);
	}

	private static ApiException refreshTokenInvalid() {
		return new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
				"Your sign-in has expired. Please sign in again.");
	}
}
