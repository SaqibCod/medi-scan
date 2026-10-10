package dev.saq.mediscan.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for Google: generates an RSA key pair in the test and mints Google-shaped ID
 * tokens with it.
 *
 * <p>No test ever calls Google (CLAUDE.md, "Auth tests"). Reaching the real service would make
 * the suite depend on the network, on a live client id, and on a human being signed in - and
 * it would still not let a test mint the deliberately wrong tokens that matter most: the wrong
 * audience, the wrong issuer, an expired token, a bad signature.
 *
 * <p>RS256 and a {@code kid}, matching what Google actually publishes, so the decoder under
 * test selects a key the same way it would in production.
 */
public final class TestGoogleIssuer {

	public static final String KEY_ID = "test-google-key-1";
	public static final String ISSUER = "https://accounts.google.com";
	public static final String ISSUER_BARE = "accounts.google.com";
	public static final String CLIENT_ID = "1234567890-testclient.apps.googleusercontent.com";

	private final RSAKey key;

	public TestGoogleIssuer() {
		try {
			this.key = new RSAKeyGenerator(2048)
					.keyID(KEY_ID)
					.keyUse(KeyUse.SIGNATURE)
					.algorithm(JWSAlgorithm.RS256)
					.generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("could not generate a test RSA key", ex);
		}
	}

	/**
	 * A decoder that verifies this issuer's signatures, for the verifier's test constructor.
	 *
	 * <p>Only the signature and key half: the claim validators come from the production code
	 * under test, so the issuer, audience, expiry and subject rules being exercised are the
	 * real ones.
	 */
	public NimbusJwtDecoder decoder() {
		JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));
		return NimbusJwtDecoder.withJwkSource(source)
				.jwsAlgorithm(SignatureAlgorithm.RS256)
				.build();
	}

	/** A decoder that fetches the key set over HTTP, for the one URI-based test. */
	public NimbusJwtDecoder decoderFrom(String jwkSetUri) {
		return NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
				.jwsAlgorithm(SignatureAlgorithm.RS256)
				.build();
	}

	/** The public key set, as Google would publish it. */
	public String publicJwkSetJson() {
		return new JWKSet(key.toPublicJWK()).toString();
	}

	/**
	 * Serves {@link #publicJwkSetJson()} on a loopback port, so the URI-based decoder can be
	 * exercised without the network.
	 *
	 * <p>The caller stops the server; the JDK's own {@link HttpServer} is used rather than a
	 * new test dependency.
	 */
	public JwkServer startJwkServer() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			byte[] body = publicJwkSetJson().getBytes(StandardCharsets.UTF_8);

			server.createContext("/certs", exchange -> {
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			server.start();

			String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
			return new JwkServer(server, uri);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not start the test JWKS server", ex);
		}
	}

	/** A valid token: the https issuer, the test client id, a name, expiring in an hour. */
	public String validToken(String sub, String name) {
		return token(sub, name, ISSUER, CLIENT_ID, Instant.now().plus(1, ChronoUnit.HOURS));
	}

	/** A valid token under the bare {@code accounts.google.com} issuer, which Google also uses. */
	public String validTokenBareIssuer(String sub, String name) {
		return token(sub, name, ISSUER_BARE, CLIENT_ID, Instant.now().plus(1, ChronoUnit.HOURS));
	}

	public String tokenWithIssuer(String sub, String issuer) {
		return token(sub, "Test User", issuer, CLIENT_ID, Instant.now().plus(1, ChronoUnit.HOURS));
	}

	public String tokenWithAudience(String sub, String audience) {
		return token(sub, "Test User", ISSUER, audience, Instant.now().plus(1, ChronoUnit.HOURS));
	}

	public String expiredToken(String sub) {
		return token(sub, "Test User", ISSUER, CLIENT_ID, Instant.now().minus(1, ChronoUnit.HOURS));
	}

	/** A token with no {@code sub}, which must never produce an account. */
	public String tokenWithoutSubject() {
		return token(null, "Test User", ISSUER, CLIENT_ID, Instant.now().plus(1, ChronoUnit.HOURS));
	}

	/** A token with the given {@code name} claim, for display-name handling. */
	public String tokenWithName(String sub, String name) {
		return token(sub, name, ISSUER, CLIENT_ID, Instant.now().plus(1, ChronoUnit.HOURS));
	}

	/**
	 * A token signed by a different key pair: structurally perfect, signature wrong.
	 *
	 * <p>Keeps this issuer's {@code kid}, so the decoder finds a key and then fails the
	 * signature check - which is the interesting path, rather than "no such key".
	 */
	public String tokenWithBadSignature(String sub) {
		return new TestGoogleIssuer().validToken(sub, "Test User");
	}

	private String token(String sub, String name, String issuer, String audience,
			Instant expiresAt) {

		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(issuer)
				.audience(audience)
				.issueTime(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
				.expirationTime(Date.from(expiresAt));

		if (sub != null) {
			claims.subject(sub);
		}
		if (name != null) {
			claims.claim("name", name);
		}
		// Claims Google sends that Medi-Scan must ignore. Present here so the tests prove
		// they are dropped rather than merely absent (CLAUDE.md rule 13).
		claims.claim("email", "someone@example.com");
		claims.claim("email_verified", true);
		claims.claim("picture", "https://example.invalid/photo.jpg");
		claims.claim("locale", "en");

		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
				.keyID(KEY_ID)
				.type(com.nimbusds.jose.JOSEObjectType.JWT)
				.build();

		SignedJWT jwt = new SignedJWT(header, claims.build());
		try {
			jwt.sign(new RSASSASigner(key));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("could not sign the test token", ex);
		}
		return jwt.serialize();
	}

	/** A running JWK set server and the URI it serves. */
	public record JwkServer(HttpServer server, String uri) implements AutoCloseable {

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
