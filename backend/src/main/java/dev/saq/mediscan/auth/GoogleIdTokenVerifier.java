package dev.saq.mediscan.auth;

import java.text.ParseException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.ErrorCode;

/**
 * Verifies Google ID tokens, fully, every time (CLAUDE.md rule 12).
 *
 * <p>Signature against Google's published keys, issuer, audience and expiry are all checked by
 * Spring Security and Nimbus. Nothing here decodes a token without verifying it, and nothing
 * here parses JWT structure by hand.
 *
 * <p><strong>Not a {@code JwtDecoder} bean.</strong> The decoder is built and held privately,
 * because the application's own access-token decoder is the single {@code JwtDecoder} bean and
 * a second one would make Boot's autoconfiguration and the {@code oauth2ResourceServer}
 * decoder lookup ambiguous (LLD 5, D9).
 *
 * <p><strong>Key caching</strong> is Nimbus's, configured by Spring:
 * {@code withJwkSetUri} produces a {@code CachingJWKSetSource} with a five-minute TTL and a
 * fifteen-second refresh timeout, with refresh-ahead and rate limiting switched off. Google's
 * own key rotation needs no special handling, because Nimbus re-fetches and re-selects when a
 * {@code kid} is not in the cached set.
 *
 * <p><strong>Every failure is {@code 401 GOOGLE_TOKEN_INVALID}</strong> and is logged as a
 * category only - never the token, never the subject, never the name. The category is derived
 * from exception and error-code types, never from message text, which is why the claim
 * validator below uses its own error codes instead of the framework's blanket
 * {@code invalid_token}.
 */
@Component
public class GoogleIdTokenVerifier {

	private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);

	/** Google issues tokens under both spellings, and both are legitimate. */
	static final Set<String> VALID_ISSUERS = Set.of("accounts.google.com",
			"https://accounts.google.com");

	static final String ERROR_ISSUER = "google_issuer";
	static final String ERROR_AUDIENCE = "google_audience";
	static final String ERROR_SUBJECT = "google_subject";

	private final JwtDecoder decoder;
	private final boolean enabled;

	/**
	 * Explicitly annotated: this class has two constructors, and without it Spring looks for a
	 * no-arg one.
	 */
	@Autowired
	public GoogleIdTokenVerifier(AuthProperties properties, Clock clock) {
		this(properties, clock, () -> NimbusJwtDecoder
				.withJwkSetUri(properties.google().jwkSetUri())
				// RS256 only. Google signs with it, and naming it means a token arriving
				// under any other algorithm finds no usable key.
				.jwsAlgorithm(SignatureAlgorithm.RS256)
				.build());
	}

	/**
	 * Takes the signature-verifying decoder from a factory so tests can supply one built on a
	 * local key pair.
	 *
	 * <p>The factory produces only the signature and key half; the claim validators are
	 * attached here, so a test exercises the same issuer, audience, expiry and subject rules
	 * that production uses. No test ever reaches Google.
	 */
	GoogleIdTokenVerifier(AuthProperties properties, Clock clock,
			Supplier<NimbusJwtDecoder> decoderFactory) {

		this.enabled = properties.google().isConfigured();

		if (!this.enabled) {
			// One line, at startup, so a local run that cannot sign in says why.
			log.warn("Google sign-in is disabled: GOOGLE_CLIENT_ID is not set. "
					+ "POST /api/auth/google will answer GOOGLE_TOKEN_INVALID.");
			this.decoder = null;
			return;
		}

		NimbusJwtDecoder nimbus = decoderFactory.get();
		nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
				JwtTypeValidator.jwt(),
				// The same expiry rules, and the same token_expired code, as the app's own
				// tokens. Here it only feeds the log category.
				new ExpiryValidator(properties.jwt().clockSkew(), clock),
				new GoogleClaimsValidator(properties.google().clientId()))));
		this.decoder = nimbus;
	}

	/**
	 * Verifies the token and returns the only two claims this application keeps.
	 *
	 * @throws ApiException {@code 401 GOOGLE_TOKEN_INVALID} for every kind of failure. The
	 *     caller gets no detail about which check failed: an attacker probing with forged
	 *     tokens learns nothing, and a real client has nothing to do differently either way
	 */
	public GoogleIdentity verify(String idToken) {
		if (!enabled) {
			throw rejected("sign_in_disabled");
		}
		if (idToken == null || idToken.isBlank()) {
			throw rejected("malformed");
		}

		Jwt jwt;
		try {
			jwt = decoder.decode(idToken);
		}
		catch (JwtValidationException ex) {
			// Claim failures: the codes are ours, so the category is readable.
			throw rejected(categoryOf(ex));
		}
		catch (BadJwtException ex) {
			// Structure and signature failures. BadJWSException ("Invalid signature") and
			// BadJOSEException ("no matching key(s) found") both arrive here.
			throw rejected(ex.getCause() instanceof ParseException ? "malformed" : "signature");
		}
		catch (JwtException ex) {
			// NimbusJwtDecoder wraps RemoteKeySourceException and JOSEException in the plain
			// JwtException, so this branch means Google's keys could not be fetched or used.
			// WARN, not INFO: nobody can sign in while this is happening, and it is not the
			// caller's fault.
			log.warn("google token rejected reason=jwks_unavailable");
			throw invalidToken();
		}

		// Only these two claims are read. email, email_verified, picture, locale and hd are
		// all present in a real token and all deliberately dropped (CLAUDE.md rule 13).
		return GoogleIdentity.of(jwt.getSubject(), jwt.getClaimAsString("name"));
	}

	private static String categoryOf(JwtValidationException ex) {
		for (OAuth2Error error : ex.getErrors()) {
			switch (error.getErrorCode()) {
				case ERROR_ISSUER -> {
					return "issuer";
				}
				case ERROR_AUDIENCE -> {
					return "audience";
				}
				case ERROR_SUBJECT -> {
					return "subject";
				}
				case ExpiryValidator.TOKEN_EXPIRED -> {
					return "expired";
				}
				default -> {
					// Keep looking: a token can fail several checks at once, and the specific
					// categories are more useful than the generic one.
				}
			}
		}
		return "claims";
	}

	private static ApiException rejected(String reason) {
		// Category only. Never the token, the sub, or the name (LLD 12).
		log.info("google token rejected reason={}", reason);
		return invalidToken();
	}

	private static ApiException invalidToken() {
		return new ApiException(ErrorCode.GOOGLE_TOKEN_INVALID,
				"Google sign-in could not be verified. Please try again.");
	}

	/**
	 * Issuer, audience and subject, each with its own error code.
	 *
	 * <p>One validator rather than three because the checks share a purpose and the first
	 * failure is the useful one. Its own codes rather than {@code JwtClaimValidator}'s blanket
	 * {@code invalid_token}, because the log category has to come from a code and never from
	 * a description string.
	 */
	static final class GoogleClaimsValidator implements OAuth2TokenValidator<Jwt> {

		private final String audience;

		GoogleClaimsValidator(String audience) {
			this.audience = audience;
		}

		@Override
		public OAuth2TokenValidatorResult validate(Jwt jwt) {
			String issuer = jwt.getClaimAsString(JwtClaimNames.ISS);
			if (issuer == null || !VALID_ISSUERS.contains(issuer)) {
				return failure(ERROR_ISSUER, "unexpected issuer");
			}

			List<String> audiences = jwt.getAudience();
			if (audiences == null || !audiences.contains(audience)) {
				// This is the check that stops a token minted for a different application
				// from signing someone in here.
				return failure(ERROR_AUDIENCE, "unexpected audience");
			}

			String subject = jwt.getSubject();
			if (subject == null || subject.isBlank()) {
				return failure(ERROR_SUBJECT, "missing subject");
			}

			return OAuth2TokenValidatorResult.success();
		}

		private static OAuth2TokenValidatorResult failure(String code, String description) {
			return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
		}
	}
}
