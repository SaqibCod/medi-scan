package dev.saq.mediscan.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.user.UserRole;

/**
 * Signs the app's own access tokens.
 *
 * <p>Claims are the ones in contract section 2.3: {@code iss}, {@code aud}, {@code sub} (the
 * user's own id, never the Google {@code sub}), {@code role}, {@code iat}, {@code exp} and
 * {@code jti}. The client treats the token as opaque; these claims exist for this API's own
 * resource server to check.
 *
 * <p>The {@code kid} header is set explicitly on every token, which is what lets the decoder
 * pick the right key after a rotation. It matters more than it looks: Nimbus treats an absent
 * {@code kid} as "any key", so with a single key in the ring an unset {@code kid} signs
 * perfectly happily and only breaks when a second key is added (LLD 6.2, D10).
 */
@Component
public class AccessTokenIssuer {

	private final JwtEncoder encoder;
	private final SigningKeyRing keyRing;
	private final String issuer;
	private final String audience;
	private final Duration ttl;
	private final Clock clock;

	public AccessTokenIssuer(SigningKeyRing keyRing, AuthProperties properties, Clock clock) {
		this.encoder = new NimbusJwtEncoder(keyRing.jwkSource());
		this.keyRing = keyRing;
		this.issuer = properties.jwt().issuer();
		this.audience = properties.jwt().audience();
		this.ttl = properties.jwt().accessTtl();
		this.clock = clock;
	}

	/**
	 * A signed access token for this user and role.
	 *
	 * <p>The role is passed in rather than read from the user row, because the caller has just
	 * recomputed it from {@code ADMIN_GOOGLE_SUBS} and that is the value the token must carry
	 * (docs/dataflow.md section 3.2).
	 */
	public IssuedAccessToken issue(UUID userId, UserRole role) {
		Instant now = Instant.now(clock);
		Instant expiresAt = now.plus(ttl);

		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
				.type("JWT")
				.keyId(keyRing.currentKid())
				.build();

		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.audience(List.of(audience))
				.subject(userId.toString())
				.claim("role", role.name())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.id(UUID.randomUUID().toString())
				.build();

		Jwt jwt = encoder.encode(JwtEncoderParameters.from(header, claims));
		return new IssuedAccessToken(jwt.getTokenValue(), expiresAt);
	}

	/**
	 * A freshly signed access token and when it expires.
	 *
	 * @param token the compact JWT. Returned to the client and never stored or logged
	 * @param expiresAt lets the client refresh early instead of waiting for a 401
	 */
	public record IssuedAccessToken(String token, Instant expiresAt) {

		/** Never prints {@link #token()}: it is a bearer credential (CLAUDE.md rule 14). */
		@Override
		public String toString() {
			return "IssuedAccessToken[expiresAt=" + expiresAt + ", token=***]";
		}
	}
}
