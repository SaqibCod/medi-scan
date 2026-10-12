package dev.saq.mediscan.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Authentication configuration, bound from {@code mediscan.auth.*}.
 *
 * <p>Separate from {@link dev.saq.mediscan.config.MediScanProperties} because this subtree is
 * the only one holding a secret, and keeping it in its own type means the {@code toString()}
 * that hides that secret covers a small, auditable surface. It still sits under the
 * {@code mediscan.*} root, so the environment-to-property mapping stays in one block of
 * {@code application.yml} (LLD 11).
 *
 * <p>{@link Jwt#signingKeys()} is the one secret here. {@link Google#clientId()} is a public
 * value - it ships in the frontend bundle - but it is still not printed, because a properties
 * record's {@code toString} reaches logs and there is no reason for it to appear there.
 *
 * @param adminSubjects comma-separated Google {@code sub} ids that get {@code ADMIN}, from
 *     {@code ADMIN_GOOGLE_SUBS}. The only way to grant the role (CLAUDE.md rule 17)
 */
@ConfigurationProperties("mediscan.auth")
@Validated
public record AuthProperties(
		@Valid @NotNull Google google,
		@Valid @NotNull Jwt jwt,
		@Valid @NotNull Refresh refresh,
		String adminSubjects) {

	/**
	 * @param clientId the OAuth web client id every Google ID token must be addressed to,
	 *     from {@code GOOGLE_CLIENT_ID}. Blank disables Google sign-in, which is allowed in
	 *     {@code local} and rejected at startup in {@code prod} (LLD 5)
	 * @param jwkSetUri where Google publishes its signing keys
	 */
	public record Google(String clientId, @NotBlank String jwkSetUri) {

		/** True when sign-in is configured. Blank means the endpoint always refuses. */
		public boolean isConfigured() {
			return clientId != null && !clientId.isBlank();
		}

		@Override
		public String toString() {
			return "Google[configured=" + isConfigured() + "]";
		}
	}

	/**
	 * @param signingKeys {@code kid:base64key} pairs, comma separated, from
	 *     {@code JWT_SIGNING_KEYS}. Parsed and validated by {@link SigningKeyRing}
	 * @param currentKid which of those keys signs new tokens, from {@code JWT_CURRENT_KID}
	 * @param issuer the {@code iss} claim this API issues and accepts
	 * @param audience the {@code aud} claim this API issues and requires
	 * @param accessTtl how long an access token is valid
	 * @param clockSkew tolerance applied to {@code exp} and {@code nbf}
	 */
	public record Jwt(
			String signingKeys,
			String currentKid,
			@NotBlank String issuer,
			@NotBlank String audience,
			@NotNull Duration accessTtl,
			@NotNull Duration clockSkew) {

		/**
		 * Never prints {@link #signingKeys()}.
		 *
		 * <p>Not even partially, and not even its length: this is the key that forges access
		 * tokens for any user, and a properties record's {@code toString} is one
		 * {@code log.debug(properties)} away from a log file (CLAUDE.md rule 14).
		 */
		@Override
		public String toString() {
			return "Jwt[issuer=" + issuer + ", audience=" + audience + ", currentKid=" + currentKid
					+ ", accessTtl=" + accessTtl + ", signingKeys=***]";
		}
	}

	/**
	 * @param ttl how long a single refresh token lives
	 * @param familyMaxAge how long a family may live from the original sign-in, whatever
	 *     happens to the individual tokens inside it
	 */
	public record Refresh(@NotNull Duration ttl, @NotNull Duration familyMaxAge) {
	}
}
