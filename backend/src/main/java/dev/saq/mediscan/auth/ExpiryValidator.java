package dev.saq.mediscan.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Checks {@code exp} and {@code nbf}, and reports an expired token under its own error code.
 *
 * <p>This exists so the API can answer {@code 401 TOKEN_EXPIRED} rather than
 * {@code TOKEN_INVALID}. The distinction is not cosmetic: the client refreshes once and
 * retries on either, but {@code TOKEN_EXPIRED} is the ordinary case every fifteen minutes,
 * while {@code TOKEN_INVALID} means something is wrong with the token itself.
 *
 * <p>Spring's own {@code JwtTimestampValidator} reports both expiry and {@code nbf} failures as
 * {@code invalid_token}, which is indistinguishable from a bad signature. It is also
 * {@code final}, so it cannot be subclassed - which is why
 * {@link AccessTokenDecoderConfig} assembles the validator list by hand instead of calling
 * {@code JwtValidators.createDefaultWithValidators}, a helper that would re-add the default
 * alongside this one (LLD 6.3, D3).
 *
 * <p>The {@link #TOKEN_EXPIRED} code travels to the entry point inside the cause of the
 * authentication failure; see {@code ProblemAuthenticationEntryPoint}. Error descriptions are
 * deliberately plain, because nothing is ever allowed to match on them (D4).
 */
public class ExpiryValidator implements OAuth2TokenValidator<Jwt> {

	/**
	 * The custom error code an expired access token carries.
	 *
	 * <p>Not one of {@link OAuth2ErrorCodes}: those are the RFC 6750 values, and none of them
	 * distinguishes expiry. The string is internal to this application.
	 */
	public static final String TOKEN_EXPIRED = "token_expired";

	private final Duration clockSkew;
	private final Clock clock;

	public ExpiryValidator(Duration clockSkew, Clock clock) {
		this.clockSkew = clockSkew;
		this.clock = clock;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		Instant now = Instant.now(clock);

		Instant expiresAt = jwt.getExpiresAt();
		if (expiresAt == null) {
			// Every token this application issues has one, so a token without `exp` was not
			// issued here - or was altered. Not "expired": there is nothing to expire.
			return failure(OAuth2ErrorCodes.INVALID_TOKEN, "exp is required");
		}
		if (now.minus(clockSkew).isAfter(expiresAt)) {
			return failure(TOKEN_EXPIRED, "the token has expired");
		}

		// nbf is optional, matching the framework default. Access tokens are valid from the
		// moment they are issued, so this application never sets one - but a token that does
		// carry one must still be honoured.
		Instant notBefore = jwt.getNotBefore();
		if (notBefore != null && now.plus(clockSkew).isBefore(notBefore)) {
			return failure(OAuth2ErrorCodes.INVALID_TOKEN, "the token is not valid yet");
		}

		return OAuth2TokenValidatorResult.success();
	}

	private static OAuth2TokenValidatorResult failure(String code, String description) {
		return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
	}
}
