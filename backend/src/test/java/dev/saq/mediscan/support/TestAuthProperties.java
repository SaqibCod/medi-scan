package dev.saq.mediscan.support;

import java.time.Duration;

import dev.saq.mediscan.auth.AuthProperties;

/**
 * {@link AuthProperties} for unit tests that construct a collaborator directly.
 *
 * <p>Same reason as {@link TestProperties}: the record has three nested records, so building
 * it inline buries the one value a test cares about. These factories start from the
 * {@code application.yml} defaults and let a test override only what it is testing.
 *
 * <p>The signing keys here are fixed test values, used by no other environment. Two of them,
 * with the current one second, because a one-key ring hides a missing {@code kid} (LLD 6.2).
 */
public final class TestAuthProperties {

	/** 32 bytes, base64: the minimum {@code SigningKeyRing} accepts. */
	public static final String KEY_ONE = "bWVkaS1zY2FuLXRlc3Qtc2lnbmluZy1rZXktbm8xISE=";
	public static final String KEY_TWO = "bWVkaS1zY2FuLXRlc3Qtc2lnbmluZy1rZXktbm8yISE=";
	public static final String KEY_THREE = "bWVkaS1zY2FuLXRlc3Qtc2lnbmluZy1rZXktbm8zISE=";

	public static final String KID_ONE = "test-k1";
	public static final String KID_TWO = "test-k2";

	public static final String TWO_KEYS = KID_ONE + ":" + KEY_ONE + "," + KID_TWO + ":" + KEY_TWO;

	public static final String ISSUER = "medi-scan";
	public static final String AUDIENCE = "medi-scan-api";

	private TestAuthProperties() {
	}

	/** Two signing keys, the second current, Google sign-in disabled, no admins. */
	public static AuthProperties defaults() {
		return new AuthProperties(
				new AuthProperties.Google("", "https://www.googleapis.com/oauth2/v3/certs"),
				new AuthProperties.Jwt(TWO_KEYS, KID_TWO, ISSUER, AUDIENCE,
						Duration.ofMinutes(15), Duration.ofSeconds(30)),
				new AuthProperties.Refresh(Duration.ofDays(7), Duration.ofDays(30)),
				"");
	}

	public static AuthProperties withAdminSubjects(String adminSubjects) {
		AuthProperties base = defaults();
		return new AuthProperties(base.google(), base.jwt(), base.refresh(), adminSubjects);
	}

	public static AuthProperties withSigningKeys(String signingKeys, String currentKid) {
		AuthProperties base = defaults();
		AuthProperties.Jwt jwt = base.jwt();
		return new AuthProperties(base.google(),
				new AuthProperties.Jwt(signingKeys, currentKid, jwt.issuer(), jwt.audience(),
						jwt.accessTtl(), jwt.clockSkew()),
				base.refresh(), base.adminSubjects());
	}

	public static AuthProperties withGoogleClientId(String clientId) {
		AuthProperties base = defaults();
		return new AuthProperties(
				new AuthProperties.Google(clientId, base.google().jwkSetUri()),
				base.jwt(), base.refresh(), base.adminSubjects());
	}

	public static AuthProperties withJwt(String issuer, String audience, Duration accessTtl,
			Duration clockSkew) {

		AuthProperties base = defaults();
		AuthProperties.Jwt jwt = base.jwt();
		return new AuthProperties(base.google(),
				new AuthProperties.Jwt(jwt.signingKeys(), jwt.currentKid(), issuer, audience,
						accessTtl, clockSkew),
				base.refresh(), base.adminSubjects());
	}
}
