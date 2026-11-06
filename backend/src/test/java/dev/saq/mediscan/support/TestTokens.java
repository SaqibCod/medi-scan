package dev.saq.mediscan.support;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import dev.saq.mediscan.user.UserRole;

/**
 * Mints the app's own access tokens, including deliberately wrong ones.
 *
 * <p>Needed because the production {@code AccessTokenIssuer} can only produce valid tokens, and
 * most of what the decoder has to get right is about invalid ones: an unknown {@code kid}, no
 * {@code kid} at all, the wrong issuer or audience, a token signed with a key that has been
 * rotated out, an expired token, a missing {@code role}.
 *
 * <p>Builds its own encoder over whatever key set the test asks for, rather than reusing the
 * application's - which is the only way to sign with a key the application does not have, and
 * so the only way to test a bad signature honestly.
 */
public final class TestTokens {

	private final JWKSource<SecurityContext> jwkSource;
	private final JwtEncoder encoder;
	private final String kid;
	private final boolean omitKid;
	private final Clock clock;

	private TestTokens(List<OctetSequenceKey> keys, String kid, boolean omitKid, Clock clock) {
		this.jwkSource = new ImmutableJWKSet<>(new JWKSet(List.copyOf(keys)));
		this.encoder = new NimbusJwtEncoder(this.jwkSource);
		this.kid = kid;
		this.omitKid = omitKid;
		this.clock = clock;
	}

	/**
	 * The same clock the decoder under test uses.
	 *
	 * <p>Not optional in practice: a decoder built on a fixed {@link Clock} and a token minted
	 * from the wall clock disagree about what "now" is, and every token then looks expired. A
	 * test that passes only because both happen to use real time is a test waiting to fail on
	 * a slow machine.
	 */
	public TestTokens withClock(Clock other) {
		return new TestTokens(keys(), kid, omitKid, other);
	}

	/** Signs with the test ring's current key ({@code test-k2}), as the application does. */
	public static TestTokens signingWithCurrentKey() {
		return new TestTokens(List.of(key(TestAuthProperties.KID_ONE, TestAuthProperties.KEY_ONE),
				key(TestAuthProperties.KID_TWO, TestAuthProperties.KEY_TWO)),
				TestAuthProperties.KID_TWO, false, Clock.systemUTC());
	}

	/** Signs with the test ring's older key, which is still listed and must still be accepted. */
	public static TestTokens signingWithPreviousKey() {
		return new TestTokens(List.of(key(TestAuthProperties.KID_ONE, TestAuthProperties.KEY_ONE),
				key(TestAuthProperties.KID_TWO, TestAuthProperties.KEY_TWO)),
				TestAuthProperties.KID_ONE, false, Clock.systemUTC());
	}

	/**
	 * Signs with a key the application has never heard of, under the given {@code kid}.
	 *
	 * <p>Use a known {@code kid} to test a bad signature, and an unknown one to test key
	 * lookup - the application rejects them for different reasons.
	 */
	public static TestTokens signingWithUnknownKey(String kid) {
		return new TestTokens(List.of(key(kid, TestAuthProperties.KEY_THREE)), kid, false,
				Clock.systemUTC());
	}

	/**
	 * Signs with valid key material but emits no {@code kid} header at all.
	 *
	 * <p>Two details make this work, and both are worth knowing.
	 *
	 * <p>The signing key is built <strong>without</strong> a {@code keyID}. Asking
	 * {@code NimbusJwtEncoder} to omit the header is not enough: after selecting a key it runs
	 * {@code addKeyIdentifierHeadersIfNecessary}, which copies the JWK's own {@code kid} into
	 * a header that does not have one. A key with no id leaves it nothing to copy.
	 *
	 * <p>The set holds only that one key, because the encoder refuses to choose between
	 * several when the header names none - the same trap that makes a missing {@code kid}
	 * worth testing at all (LLD 6.2, D10).
	 *
	 * <p>The material is still {@code KEY_TWO}, so the application's {@code test-k2} verifies
	 * the signature. That is the point: the token is cryptographically fine and must be
	 * refused anyway, because without a {@code kid} Nimbus would try every key in the ring.
	 */
	public static TestTokens signingWithoutKeyId() {
		return new TestTokens(List.of(keyWithoutId(TestAuthProperties.KEY_TWO)), null, true,
				Clock.systemUTC());
	}

	/** A valid token for this user and role, expiring in 15 minutes. */
	public String validFor(UUID userId, UserRole role) {
		return token(claims(userId, role.name(), TestAuthProperties.ISSUER,
				TestAuthProperties.AUDIENCE, now(), now().plus(15, ChronoUnit.MINUTES)));
	}

	/** A token that expired an hour ago, well beyond any clock skew. */
	public String expiredFor(UUID userId, UserRole role) {
		return token(claims(userId, role.name(), TestAuthProperties.ISSUER,
				TestAuthProperties.AUDIENCE, now().minus(2, ChronoUnit.HOURS),
				now().minus(1, ChronoUnit.HOURS)));
	}

	/** A token whose {@code exp} has just passed, inside the 30-second skew. */
	public String justExpiredFor(UUID userId, UserRole role) {
		return token(claims(userId, role.name(), TestAuthProperties.ISSUER,
				TestAuthProperties.AUDIENCE, now().minus(1, ChronoUnit.MINUTES),
				now().minusSeconds(5)));
	}

	public String withIssuer(UUID userId, String issuer) {
		return token(claims(userId, UserRole.USER.name(), issuer, TestAuthProperties.AUDIENCE,
				now(), now().plus(15, ChronoUnit.MINUTES)));
	}

	public String withAudience(UUID userId, String audience) {
		return token(claims(userId, UserRole.USER.name(), TestAuthProperties.ISSUER, audience,
				now(), now().plus(15, ChronoUnit.MINUTES)));
	}

	/** A token with no {@code role} claim at all. */
	public String withoutRole(UUID userId) {
		return token(claims(userId, null, TestAuthProperties.ISSUER, TestAuthProperties.AUDIENCE,
				now(), now().plus(15, ChronoUnit.MINUTES)));
	}

	/** A token whose {@code role} is not one this application knows. */
	public String withRole(UUID userId, String role) {
		return token(claims(userId, role, TestAuthProperties.ISSUER, TestAuthProperties.AUDIENCE,
				now(), now().plus(15, ChronoUnit.MINUTES)));
	}

	/** A token whose {@code sub} is not a UUID. */
	public String withSubject(String subject) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(TestAuthProperties.ISSUER)
				.audience(List.of(TestAuthProperties.AUDIENCE))
				.subject(subject)
				.claim("role", UserRole.USER.name())
				.issuedAt(now())
				.expiresAt(now().plus(15, ChronoUnit.MINUTES))
				.id(UUID.randomUUID().toString())
				.build();
		return token(claims);
	}

	private Instant now() {
		return Instant.now(clock);
	}

	private List<OctetSequenceKey> keys() {
		return jwkSource instanceof ImmutableJWKSet<SecurityContext> immutable
				? immutable.getJWKSet().getKeys().stream().map(OctetSequenceKey.class::cast).toList()
				: List.of();
	}

	private JwtClaimsSet claims(UUID userId, String role, String issuer, String audience,
			Instant issuedAt, Instant expiresAt) {

		JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
				.issuer(issuer)
				.audience(List.of(audience))
				.subject(userId.toString())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.id(UUID.randomUUID().toString());

		if (role != null) {
			builder.claim("role", role);
		}
		return builder.build();
	}

	private String token(JwtClaimsSet claims) {
		JwsHeader.Builder header = JwsHeader.with(MacAlgorithm.HS256).type("JWT");
		if (!omitKid) {
			header.keyId(kid);
		}
		return encoder.encode(JwtEncoderParameters.from(header.build(), claims)).getTokenValue();
	}

	private static OctetSequenceKey key(String kid, String base64Key) {
		return new OctetSequenceKey.Builder(java.util.Base64.getDecoder().decode(base64Key))
				.keyID(kid)
				.algorithm(JWSAlgorithm.HS256)
				.build();
	}

	private static OctetSequenceKey keyWithoutId(String base64Key) {
		return new OctetSequenceKey.Builder(java.util.Base64.getDecoder().decode(base64Key))
				.algorithm(JWSAlgorithm.HS256)
				.build();
	}
}
