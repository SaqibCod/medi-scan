package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@link ExpiryValidator} is the only reason the API can answer {@code TOKEN_EXPIRED} rather
 * than a flat {@code TOKEN_INVALID}, so the error <em>code</em> is the assertion that matters
 * here - not the message, which nothing is allowed to match on (LLD 6.3, D4).
 *
 * <p>Time comes from a fixed {@link Clock}, per {@code backend/CLAUDE.md}: a test that waits
 * for a token to expire is either slow or flaky.
 */
class ExpiryValidatorTest {

	private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
	private static final Duration SKEW = Duration.ofSeconds(30);

	private final ExpiryValidator validator =
			new ExpiryValidator(SKEW, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	@DisplayName("a live token passes")
	void acceptsLiveToken() {
		OAuth2TokenValidatorResult result = validator.validate(jwt(NOW.plusSeconds(600), null));

		assertThat(result.hasErrors()).isFalse();
	}

	@Test
	@DisplayName("a token expired beyond the skew carries the token_expired code")
	void expiredBeyondSkew() {
		OAuth2TokenValidatorResult result = validator.validate(jwt(NOW.minusSeconds(60), null));

		assertThat(errorCodes(result)).containsExactly(ExpiryValidator.TOKEN_EXPIRED);
	}

	@Test
	@DisplayName("a token that expired within the skew is still accepted")
	void expiredWithinSkewIsAccepted() {
		// 29 seconds past exp, with 30 seconds of tolerance: a slightly fast clock on the
		// client or a slightly slow one here should not log anyone out.
		OAuth2TokenValidatorResult result = validator.validate(jwt(NOW.minusSeconds(29), null));

		assertThat(result.hasErrors()).isFalse();
	}

	@Test
	@DisplayName("the skew boundary is exclusive, so exactly at the edge is still valid")
	void atSkewBoundary() {
		OAuth2TokenValidatorResult result = validator.validate(jwt(NOW.minus(SKEW), null));

		assertThat(result.hasErrors()).isFalse();
	}

	@Test
	@DisplayName("a missing exp is invalid, not expired")
	void missingExpiryIsInvalid() {
		// Every token this application issues has exp, so one without it was not issued here.
		// Reporting it as expired would tell the client to refresh, which would not help.
		OAuth2TokenValidatorResult result = validator.validate(jwt(null, null));

		assertThat(errorCodes(result)).containsExactly(OAuth2ErrorCodes.INVALID_TOKEN);
	}

	@Test
	@DisplayName("an nbf in the future is invalid, not expired")
	void notYetValidIsInvalid() {
		OAuth2TokenValidatorResult result =
				validator.validate(jwt(NOW.plusSeconds(600), NOW.plusSeconds(120)));

		assertThat(errorCodes(result)).containsExactly(OAuth2ErrorCodes.INVALID_TOKEN);
	}

	@Test
	@DisplayName("an nbf within the skew is accepted")
	void notBeforeWithinSkew() {
		OAuth2TokenValidatorResult result =
				validator.validate(jwt(NOW.plusSeconds(600), NOW.plusSeconds(20)));

		assertThat(result.hasErrors()).isFalse();
	}

	@Test
	@DisplayName("an absent nbf is fine: this application never sets one")
	void absentNotBeforeIsFine() {
		OAuth2TokenValidatorResult result = validator.validate(jwt(NOW.plusSeconds(600), null));

		assertThat(result.hasErrors()).isFalse();
	}

	@Test
	@DisplayName("expiry is reported before nbf, so an old token reads as expired")
	void expiryWinsOverNotBefore() {
		OAuth2TokenValidatorResult result =
				validator.validate(jwt(NOW.minusSeconds(600), NOW.plusSeconds(600)));

		assertThat(errorCodes(result)).containsExactly(ExpiryValidator.TOKEN_EXPIRED);
	}

	private static Iterable<String> errorCodes(OAuth2TokenValidatorResult result) {
		return result.getErrors().stream().map(OAuth2Error::getErrorCode).toList();
	}

	/** A {@link Jwt} with only the timestamp claims this validator looks at. */
	private static Jwt jwt(Instant expiresAt, Instant notBefore) {
		Jwt.Builder builder = Jwt.withTokenValue("token-value-is-never-read-here")
				.header("alg", "HS256")
				.header("kid", "test-k2")
				.subject("11111111-1111-1111-1111-111111111111")
				.claims(claims -> claims.putAll(Map.of()));

		if (expiresAt != null) {
			builder.expiresAt(expiresAt);
		}
		if (notBefore != null) {
			builder.notBefore(notBefore);
		}
		return builder.build();
	}
}
