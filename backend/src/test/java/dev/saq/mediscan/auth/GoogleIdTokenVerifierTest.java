package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.support.TestAuthProperties;
import dev.saq.mediscan.support.TestGoogleIssuer;

/**
 * Google ID token verification, against a local key pair rather than Google
 * (CLAUDE.md, "Auth tests").
 *
 * <p>The verifier is built through its test constructor, which supplies only the
 * signature-verifying decoder. The issuer, audience, expiry and subject rules come from the
 * production code, so these are the real checks and not a re-implementation of them.
 *
 * <p>Every rejection is the same {@code 401 GOOGLE_TOKEN_INVALID}: the API never tells a
 * caller which check failed, so there is nothing to probe with forged tokens.
 */
class GoogleIdTokenVerifierTest {

	private static final String SUB = "116947382910473829104";

	private final TestGoogleIssuer google = new TestGoogleIssuer();
	private final AuthProperties properties =
			TestAuthProperties.withGoogleClientId(TestGoogleIssuer.CLIENT_ID);
	private final GoogleIdTokenVerifier verifier =
			new GoogleIdTokenVerifier(properties, Clock.systemUTC(), google::decoder);

	@Test
	@DisplayName("a valid token yields the subject and display name")
	void acceptsValidToken() {
		GoogleIdentity identity = verifier.verify(google.validToken(SUB, "Priya Raman"));

		assertThat(identity.sub()).isEqualTo(SUB);
		assertThat(identity.displayName()).isEqualTo("Priya Raman");
	}

	@ParameterizedTest
	@ValueSource(strings = { TestGoogleIssuer.ISSUER, TestGoogleIssuer.ISSUER_BARE })
	@DisplayName("both of Google's issuer spellings are accepted")
	void acceptsBothIssuerForms(String issuer) {
		// Google really does use both, and rejecting either would break sign-in for some
		// users with no obvious pattern (plan section 4.9).
		String token = google.tokenWithIssuer(SUB, issuer);

		assertThat(verifier.verify(token).sub()).isEqualTo(SUB);
	}

	@Test
	@DisplayName("a token for another application's client id is rejected")
	void rejectsWrongAudience() {
		// The check that stops someone replaying a Google token minted for a different app.
		String token = google.tokenWithAudience(SUB, "999-someoneelse.apps.googleusercontent.com");

		assertRejected(token);
	}

	@Test
	@DisplayName("a token from an unexpected issuer is rejected")
	void rejectsWrongIssuer() {
		assertRejected(google.tokenWithIssuer(SUB, "https://accounts.evil.invalid"));
	}

	@Test
	@DisplayName("an expired token is rejected")
	void rejectsExpiredToken() {
		assertRejected(google.expiredToken(SUB));
	}

	@Test
	@DisplayName("a token signed by a different key is rejected")
	void rejectsBadSignature() {
		assertRejected(google.tokenWithBadSignature(SUB));
	}

	@Test
	@DisplayName("a token with no subject is rejected, so it can never create an account")
	void rejectsMissingSubject() {
		assertRejected(google.tokenWithoutSubject());
	}

	@ParameterizedTest
	@ValueSource(strings = { "not-a-jwt", "a.b.c", "", "   " })
	@DisplayName("a malformed or empty token is rejected")
	void rejectsMalformedToken(String token) {
		assertRejected(token);
	}

	@Test
	@DisplayName("a null token is rejected rather than throwing something else")
	void rejectsNullToken() {
		assertRejected(null);
	}

	@Test
	@DisplayName("a blank name becomes 'User'")
	void blankNameFallsBack() {
		GoogleIdentity identity = verifier.verify(google.tokenWithName(SUB, "   "));

		assertThat(identity.displayName()).isEqualTo("User");
	}

	@Test
	@DisplayName("a missing name claim becomes 'User'")
	void missingNameFallsBack() {
		GoogleIdentity identity = verifier.verify(google.tokenWithName(SUB, null));

		assertThat(identity.displayName()).isEqualTo("User");
	}

	@Test
	@DisplayName("a very long name is cut to 100 characters")
	void longNameIsCapped() {
		GoogleIdentity identity = verifier.verify(google.tokenWithName(SUB, "z".repeat(250)));

		assertThat(identity.displayName()).hasSize(100);
	}

	@Test
	@DisplayName("the email, picture and locale claims are dropped, not just unused")
	void dropsEveryOtherClaim() {
		// TestGoogleIssuer puts real-looking email, email_verified, picture and locale claims
		// in every token. GoogleIdentity has nowhere to put them, so this asserts the shape
		// of what survives verification (CLAUDE.md rule 13).
		GoogleIdentity identity = verifier.verify(google.validToken(SUB, "Priya Raman"));

		assertThat(identity).hasToString("GoogleIdentity[sub=***, displayName=***]");
		assertThat(GoogleIdentity.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("sub", "displayName");
	}

	@Test
	@DisplayName("toString hides the subject and the name")
	void toStringHidesIdentity() {
		GoogleIdentity identity = verifier.verify(google.validToken(SUB, "Priya Raman"));

		assertThat(identity.toString()).doesNotContain(SUB).doesNotContain("Priya");
	}

	@Test
	@DisplayName("with no client id configured, every token is refused")
	void refusesEverythingWhenDisabled() {
		// The local-profile behaviour: sign-in is off, and the endpoint says so consistently
		// rather than attempting to reach Google (LLD 5).
		GoogleIdTokenVerifier disabled = new GoogleIdTokenVerifier(
				TestAuthProperties.withGoogleClientId(""), Clock.systemUTC(), google::decoder);

		assertThatThrownBy(() -> disabled.verify(google.validToken(SUB, "Priya Raman")))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).code())
				.isEqualTo(ErrorCode.GOOGLE_TOKEN_INVALID);
	}

	@Test
	@DisplayName("keys can be fetched from a JWK set URI over HTTP")
	void verifiesAgainstJwkSetUri() {
		// Covers the URI-based configuration production uses, without calling Google: the
		// key set is served from a loopback port by the JDK's own HttpServer.
		try (TestGoogleIssuer.JwkServer jwks = google.startJwkServer()) {
			GoogleIdTokenVerifier overHttp = new GoogleIdTokenVerifier(properties,
					Clock.systemUTC(), () -> google.decoderFrom(jwks.uri()));

			assertThat(overHttp.verify(google.validToken(SUB, "Priya Raman")).sub())
					.isEqualTo(SUB);
		}
	}

	@Test
	@DisplayName("an unreachable JWK set URI is refused, not surfaced as a server error")
	void refusesWhenKeysUnreachable() {
		// Port 1 on loopback refuses connections immediately. Nobody can sign in while this
		// is true, but the caller still gets a 401 rather than a 500 (LLD 5).
		GoogleIdTokenVerifier unreachable = new GoogleIdTokenVerifier(properties,
				Clock.systemUTC(), () -> google.decoderFrom("http://127.0.0.1:1/certs"));

		assertRejected(unreachable, google.validToken(SUB, "Priya Raman"));
	}

	private void assertRejected(String token) {
		assertRejected(verifier, token);
	}

	private static void assertRejected(GoogleIdTokenVerifier verifier, String token) {
		assertThatThrownBy(() -> verifier.verify(token))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).code())
				.isEqualTo(ErrorCode.GOOGLE_TOKEN_INVALID);
	}
}
