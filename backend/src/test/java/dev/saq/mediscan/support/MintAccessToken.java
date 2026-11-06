package dev.saq.mediscan.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import dev.saq.mediscan.auth.AccessTokenIssuer;
import dev.saq.mediscan.auth.AuthProperties;
import dev.saq.mediscan.auth.SigningKeyRing;
import dev.saq.mediscan.user.UserRole;

/**
 * Prints an access token for manual testing against a running server.
 *
 * <p>A command-line utility in test sources, deliberately <strong>not</strong> an endpoint.
 * An endpoint that minted tokens would be a way to impersonate any user, and it would exist in
 * production whatever guard was put on it.
 *
 * <p>Reads the same {@code JWT_SIGNING_KEYS} and {@code JWT_CURRENT_KID} as the server, so the
 * tokens it prints are ones that server will accept - or, for the failure modes, reject for
 * exactly the reason being tested.
 *
 * <pre>
 * java -cp "target/test-classes;target/classes;$(cat cp.txt)" \
 *      dev.saq.mediscan.support.MintAccessToken valid &lt;userId&gt;
 * </pre>
 *
 * <p>Modes: {@code valid}, {@code admin}, {@code expired}, {@code unknown-kid}.
 */
public final class MintAccessToken {

	private MintAccessToken() {
	}

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("usage: MintAccessToken <valid|admin|expired|unknown-kid> [userId]");
			System.exit(2);
		}

		String mode = args[0];
		UUID userId = args.length > 1 ? UUID.fromString(args[1]) : UUID.randomUUID();

		String signingKeys = required("JWT_SIGNING_KEYS");
		String currentKid = required("JWT_CURRENT_KID");
		String issuer = env("JWT_ISSUER", "medi-scan");
		String audience = env("JWT_AUDIENCE", "medi-scan-api");

		AuthProperties properties = new AuthProperties(
				new AuthProperties.Google("", "https://www.googleapis.com/oauth2/v3/certs"),
				new AuthProperties.Jwt(signingKeys, currentKid, issuer, audience,
						Duration.ofMinutes(15), Duration.ofSeconds(30)),
				new AuthProperties.Refresh(Duration.ofDays(7), Duration.ofDays(30)),
				"");

		System.out.println(switch (mode) {
			case "valid" -> valid(properties, userId, UserRole.USER);
			case "admin" -> valid(properties, userId, UserRole.ADMIN);
			case "expired" -> expired(properties, userId);
			case "unknown-kid" -> unknownKid(properties, userId);
			default -> {
				System.err.println("unknown mode: " + mode);
				System.exit(2);
				yield "";
			}
		});
	}

	/**
	 * Signed by the production issuer, with the production clock.
	 *
	 * <p>{@code role} is whatever the caller asks for - this tool signs tokens for manual
	 * testing, not through {@code ADMIN_GOOGLE_SUBS}, so an {@code admin} token here does not
	 * require the user id to be on that list. Matches how {@code TestTokens} does it in the
	 * automated suite.
	 */
	private static String valid(AuthProperties properties, UUID userId, UserRole role) {
		return new AccessTokenIssuer(new SigningKeyRing(properties), properties, Clock.systemUTC())
				.issue(userId, role)
				.token();
	}

	/**
	 * Signed correctly but an hour past expiry.
	 *
	 * <p>Produced by running the real issuer against a clock set two hours ago, rather than by
	 * hand-building claims - so the only thing different about this token is the time.
	 */
	private static String expired(AuthProperties properties, UUID userId) {
		Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
		return new AccessTokenIssuer(new SigningKeyRing(properties), properties, twoHoursAgo)
				.issue(userId, UserRole.USER)
				.token();
	}

	/**
	 * Signed with a key the server has never heard of, under a {@code kid} it has never heard
	 * of either.
	 *
	 * <p>Built here rather than through the issuer, because the issuer can only sign with keys
	 * that are in the ring - which is the point of it.
	 */
	private static String unknownKid(AuthProperties properties, UUID userId) {
		byte[] strangerKey = new byte[32];
		new java.security.SecureRandom().nextBytes(strangerKey);

		OctetSequenceKey jwk = new OctetSequenceKey.Builder(strangerKey)
				.keyID("not-a-real-kid")
				.algorithm(JWSAlgorithm.HS256)
				.build();

		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.jwt().issuer())
				.audience(List.of(properties.jwt().audience()))
				.subject(userId.toString())
				.claim("role", UserRole.USER.name())
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(15)))
				.id(UUID.randomUUID().toString())
				.build();

		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
				.type("JWT")
				.keyId("not-a-real-kid")
				.build();

		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)))
				.encode(JwtEncoderParameters.from(header, claims))
				.getTokenValue();
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			System.err.println(name + " is not set. Export the same value the server uses.");
			System.exit(2);
		}
		return value;
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}
}
