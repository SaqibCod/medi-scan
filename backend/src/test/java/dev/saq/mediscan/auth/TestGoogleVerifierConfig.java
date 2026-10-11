package dev.saq.mediscan.auth;

import java.time.Clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import dev.saq.mediscan.support.TestAuthProperties;
import dev.saq.mediscan.support.TestGoogleIssuer;

/**
 * Replaces the Google verifier with one that trusts a key pair generated in the test.
 *
 * <p>Lives in the {@code auth} package because {@link GoogleIdTokenVerifier}'s
 * decoder-injecting constructor is package-private - the production constructor is the only
 * public one, so nothing outside this package can accidentally build a verifier that trusts
 * something other than Google.
 *
 * <p>The verifier still applies the real issuer, audience, expiry and subject validators; only
 * the source of signing keys changes. No test calls Google (CLAUDE.md, "Auth tests").
 *
 * <p>{@link TestGoogleIssuer} is a bean too, so a test can mint tokens the injected verifier
 * will accept.
 */
@TestConfiguration
public class TestGoogleVerifierConfig {

	@Bean
	TestGoogleIssuer testGoogleIssuer() {
		return new TestGoogleIssuer();
	}

	/**
	 * {@code @Primary} so it wins over the component-scanned verifier, which would otherwise
	 * make the injection into {@code AuthService} ambiguous.
	 */
	@Bean
	@Primary
	GoogleIdTokenVerifier testGoogleIdTokenVerifier(TestGoogleIssuer issuer, Clock clock) {
		return new GoogleIdTokenVerifier(
				TestAuthProperties.withGoogleClientId(TestGoogleIssuer.CLIENT_ID),
				clock, issuer::decoder);
	}
}
