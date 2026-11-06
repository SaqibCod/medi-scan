package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import dev.saq.mediscan.support.TestAuthProperties;
import dev.saq.mediscan.support.TestTokens;
import dev.saq.mediscan.user.UserRole;

/**
 * The issuer and the decoder, tested together.
 *
 * <p>Together rather than separately because the thing worth proving is the round trip: a
 * token this application signs is one it accepts, and everything else is refused. Testing the
 * decoder against hand-written tokens alone would not catch an issuer that stopped setting
 * {@code kid}, which is the mistake with the quietest failure mode (LLD 6.2, D10).
 *
 * <p>The ring here has two keys, with the current one second. That arrangement is deliberate:
 * with a single key, {@code NimbusJwtEncoder} signs happily when the header names no
 * {@code kid}, so a one-key test would pass while the bug waited for the first rotation.
 */
class AccessTokenTest {

	private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
	private static final UUID USER_ID = UUID.fromString("3f2a7c58-11d4-4f0e-9a2b-7c5e8d9f1a3b");

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final AuthProperties properties = TestAuthProperties.defaults();
	private final SigningKeyRing keyRing = new SigningKeyRing(properties);
	private final AccessTokenIssuer issuer = new AccessTokenIssuer(keyRing, properties, clock);
	private final JwtDecoder decoder =
			new AccessTokenDecoderConfig().accessTokenDecoder(keyRing, properties, clock);

	/** Mints tokens on the same clock as the decoder, or everything looks expired. */
	private TestTokens tokens(TestTokens tokens) {
		return tokens.withClock(clock);
	}

	// --- issuing -------------------------------------------------------------

	@Test
	@DisplayName("an issued token carries the claims the contract promises")
	void issuesContractClaims() {
		AccessTokenIssuer.IssuedAccessToken issued = issuer.issue(USER_ID, UserRole.USER);

		Jwt jwt = decoder.decode(issued.token());

		assertThat(jwt.getSubject()).isEqualTo(USER_ID.toString());
		assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
		// getClaimAsString, not getIssuer(): the issuer is the plain name `medi-scan`, and
		// JwtClaimAccessor.getIssuer() insists on parsing the claim as a URL.
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(TestAuthProperties.ISSUER);
		assertThat(jwt.getAudience()).containsExactly(TestAuthProperties.AUDIENCE);
		assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
		assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
		assertThat(jwt.getId()).isNotBlank();
	}

	@Test
	@DisplayName("the kid header names the current key, not just any key")
	void setsCurrentKidInHeader() {
		AccessTokenIssuer.IssuedAccessToken issued = issuer.issue(USER_ID, UserRole.ADMIN);

		Jwt jwt = decoder.decode(issued.token());

		assertThat(jwt.getHeaders()).containsEntry("kid", TestAuthProperties.KID_TWO);
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
		assertThat(jwt.getHeaders()).containsEntry("typ", "JWT");
	}

	@Test
	@DisplayName("sub is the user's own id, never a Google sub")
	void subjectIsInternalUserId() {
		// CLAUDE.md rule 13: the Google sub is not stored outside app_user and must not
		// travel in a token the browser holds.
		Jwt jwt = decoder.decode(issuer.issue(USER_ID, UserRole.USER).token());

		assertThat(UUID.fromString(jwt.getSubject())).isEqualTo(USER_ID);
	}

	@Test
	@DisplayName("the admin role reaches the token")
	void issuesAdminRole() {
		Jwt jwt = decoder.decode(issuer.issue(USER_ID, UserRole.ADMIN).token());

		assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
	}

	@Test
	@DisplayName("the issued token's toString hides the token itself")
	void issuedTokenToStringHidesToken() {
		AccessTokenIssuer.IssuedAccessToken issued = issuer.issue(USER_ID, UserRole.USER);

		assertThat(issued.toString()).doesNotContain(issued.token()).contains("token=***");
	}

	// --- validating ----------------------------------------------------------

	@Test
	@DisplayName("a token signed with an older key that is still listed is accepted")
	void acceptsPreviousKey() {
		// The whole point of key ids: rotating the signing key must not sign anyone out
		// (docs/dataflow.md section 3.7).
		String token = tokens(TestTokens.signingWithPreviousKey()).validFor(USER_ID, UserRole.USER);

		assertThat(decoder.decode(token).getSubject()).isEqualTo(USER_ID.toString());
	}

	@Test
	@DisplayName("a token with an unknown kid is rejected")
	void rejectsUnknownKid() {
		String token = tokens(TestTokens.signingWithUnknownKey("retired-k0"))
				.validFor(USER_ID, UserRole.USER);

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	@DisplayName("a token with NO kid is rejected, even though it is signed with a real key")
	void rejectsMissingKid() {
		// This is the hole RequiredKeyIdValidator closes. Nimbus reads a null kid as "any
		// key", so without that validator this token verifies against the ring (D2).
		String token = tokens(TestTokens.signingWithoutKeyId()).validFor(USER_ID, UserRole.USER);

		assertThatThrownBy(() -> decoder.decode(token))
				.isInstanceOf(JwtValidationException.class)
				.hasMessageContaining("kid");
	}

	@Test
	@DisplayName("a token with a known kid but the wrong key is rejected")
	void rejectsBadSignature() {
		String token = tokens(TestTokens.signingWithUnknownKey(TestAuthProperties.KID_TWO))
				.validFor(USER_ID, UserRole.USER);

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	@DisplayName("an expired token is rejected with the token_expired code, not invalid_token")
	void rejectsExpiredWithDistinctCode() {
		String token = tokens(TestTokens.signingWithCurrentKey()).expiredFor(USER_ID, UserRole.USER);

		assertThatThrownBy(() -> decoder.decode(token))
				.isInstanceOf(JwtValidationException.class)
				.satisfies(thrown -> assertThat(errorCodes((JwtValidationException) thrown))
						.contains(ExpiryValidator.TOKEN_EXPIRED));
	}

	@Test
	@DisplayName("the wrong issuer is rejected")
	void rejectsWrongIssuer() {
		String token = tokens(TestTokens.signingWithCurrentKey()).withIssuer(USER_ID, "some-other-api");

		assertThatThrownBy(() -> decoder.decode(token))
				.isInstanceOf(JwtValidationException.class)
				.satisfies(thrown -> assertThat(errorCodes((JwtValidationException) thrown))
						.doesNotContain(ExpiryValidator.TOKEN_EXPIRED));
	}

	@Test
	@DisplayName("the wrong audience is rejected")
	void rejectsWrongAudience() {
		String token = tokens(TestTokens.signingWithCurrentKey())
				.withAudience(USER_ID, "someone-elses-api");

		assertThatThrownBy(() -> decoder.decode(token))
				.isInstanceOf(JwtValidationException.class);
	}

	@Test
	@DisplayName("a token with no role claim is rejected")
	void rejectsMissingRole() {
		String token = tokens(TestTokens.signingWithCurrentKey()).withoutRole(USER_ID);

		assertThatThrownBy(() -> decoder.decode(token))
				.isInstanceOf(JwtValidationException.class);
	}

	@Test
	@DisplayName("a token with an unknown role is rejected before authority mapping can fail")
	void rejectsUnknownRole() {
		String token = tokens(TestTokens.signingWithCurrentKey()).withRole(USER_ID, "SUPERUSER");

		assertThatThrownBy(() -> decoder.decode(token))
				.isInstanceOf(JwtValidationException.class);
	}

	@Test
	@DisplayName("a sub that is not a UUID is rejected")
	void rejectsNonUuidSubject() {
		String token = tokens(TestTokens.signingWithCurrentKey()).withSubject("not-a-uuid");

		assertThatThrownBy(() -> decoder.decode(token))
				.isInstanceOf(JwtValidationException.class);
	}

	@Test
	@DisplayName("a malformed token is rejected without reaching a validator")
	void rejectsMalformedToken() {
		assertThatThrownBy(() -> decoder.decode("not.a.jwt"))
				.isInstanceOf(BadJwtException.class);
	}

	// --- key rotation --------------------------------------------------------

	@Test
	@DisplayName("after a key is removed from the list, its tokens stop being accepted")
	void rejectsRemovedKey() {
		String oldToken = tokens(TestTokens.signingWithPreviousKey()).validFor(USER_ID, UserRole.USER);

		// Second half of the rotation procedure: drop the retired key (contract 2.8).
		AuthProperties rotated = TestAuthProperties.withSigningKeys(
				TestAuthProperties.KID_TWO + ":" + TestAuthProperties.KEY_TWO,
				TestAuthProperties.KID_TWO);
		JwtDecoder afterRotation = new AccessTokenDecoderConfig()
				.accessTokenDecoder(new SigningKeyRing(rotated), rotated, clock);

		assertThatThrownBy(() -> afterRotation.decode(oldToken))
				.isInstanceOf(BadJwtException.class);
	}

	@Test
	@DisplayName("a newly current key signs tokens the rotated decoder accepts")
	void acceptsNewlyCurrentKey() {
		AuthProperties rotated = TestAuthProperties.withSigningKeys(
				TestAuthProperties.KID_ONE + ":" + TestAuthProperties.KEY_ONE + ","
						+ TestAuthProperties.KID_TWO + ":" + TestAuthProperties.KEY_TWO,
				TestAuthProperties.KID_ONE);
		SigningKeyRing rotatedRing = new SigningKeyRing(rotated);

		AccessTokenIssuer rotatedIssuer = new AccessTokenIssuer(rotatedRing, rotated, clock);
		JwtDecoder rotatedDecoder = new AccessTokenDecoderConfig()
				.accessTokenDecoder(rotatedRing, rotated, clock);

		Jwt jwt = rotatedDecoder.decode(rotatedIssuer.issue(USER_ID, UserRole.USER).token());

		assertThat(jwt.getHeaders()).containsEntry("kid", TestAuthProperties.KID_ONE);
	}

	private static Iterable<String> errorCodes(JwtValidationException ex) {
		return ex.getErrors().stream().map(OAuth2Error::getErrorCode).toList();
	}
}
