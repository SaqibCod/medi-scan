package dev.saq.mediscan.auth;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * The access token signing keys, parsed from {@code JWT_SIGNING_KEYS}.
 *
 * <p>Several keys so they can be rotated without signing anyone out: new tokens are signed
 * with {@code JWT_CURRENT_KID}, and any key still in the list is accepted by {@code kid}
 * (docs/dataflow.md section 3.7). HS256 is enough because one service both signs and verifies;
 * nobody else needs to check these tokens.
 *
 * <p>Everything is validated at startup rather than at the first request. A bad key is a
 * configuration mistake, and the useful moment to find out is the deploy - not when the first
 * user tries to sign in.
 *
 * <p>Nothing here ever puts key material in a message, a log line, or a {@code toString}. The
 * failure messages name the problem and the {@code kid}, which is a label, not a secret.
 */
@Component
public class SigningKeyRing {

	private static final Logger log = LoggerFactory.getLogger(SigningKeyRing.class);

	/** 256 bits, the output size of HS256's HMAC. A shorter key weakens the signature. */
	static final int MIN_KEY_BYTES = 32;

	private final JWKSet jwkSet;
	private final JWKSource<SecurityContext> jwkSource;
	private final String currentKid;

	/**
	 * Explicitly annotated because this class has two constructors: without it Spring cannot
	 * tell which to use and looks for a no-arg one instead. {@code @Autowired} on a
	 * constructor, never on a field ({@code backend/CLAUDE.md}, "Injection").
	 */
	@Autowired
	public SigningKeyRing(AuthProperties properties) {
		this(properties.jwt().signingKeys(), properties.jwt().currentKid());
	}

	/** The raw form, for tests that need to feed in deliberately broken configuration. */
	SigningKeyRing(String signingKeys, String currentKid) {
		List<JWK> keys = parse(signingKeys);

		if (currentKid == null || currentKid.isBlank()) {
			throw new IllegalStateException(
					"JWT_CURRENT_KID is not set. It must name one of the key ids in JWT_SIGNING_KEYS.");
		}
		if (keys.stream().noneMatch(key -> currentKid.equals(key.getKeyID()))) {
			throw new IllegalStateException("JWT_CURRENT_KID names key id '" + currentKid
					+ "', which is not in JWT_SIGNING_KEYS.");
		}

		this.jwkSet = new JWKSet(keys);
		this.jwkSource = new ImmutableJWKSet<>(this.jwkSet);
		this.currentKid = currentKid;

		// Count and ids only. The ids are labels that appear in token headers anyway.
		log.info("Access token signing keys loaded: {} key(s), current kid={}", keys.size(),
				currentKid);
	}

	private static List<JWK> parse(String signingKeys) {
		if (signingKeys == null || signingKeys.isBlank()) {
			throw new IllegalStateException(
					"JWT_SIGNING_KEYS is not set. Generate a key with: openssl rand -base64 32");
		}

		List<JWK> keys = new ArrayList<>();
		Set<String> seenKids = new LinkedHashSet<>();

		for (String entry : signingKeys.split(",")) {
			String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}

			// Limit 2: base64 uses '+', '/' and '=' but never ':', so the first colon is
			// always the separator and the rest of the entry is the key.
			String[] parts = trimmed.split(":", 2);
			if (parts.length != 2) {
				throw new IllegalStateException(
						"JWT_SIGNING_KEYS entry " + (keys.size() + 1)
								+ " is not in kid:base64key form.");
			}

			String kid = parts[0].trim();
			if (kid.isEmpty()) {
				throw new IllegalStateException(
						"JWT_SIGNING_KEYS entry " + (keys.size() + 1) + " has a blank key id.");
			}
			if (!seenKids.add(kid)) {
				throw new IllegalStateException(
						"JWT_SIGNING_KEYS lists key id '" + kid + "' more than once.");
			}

			keys.add(toJwk(kid, parts[1].trim()));
		}

		if (keys.isEmpty()) {
			throw new IllegalStateException("JWT_SIGNING_KEYS has no usable entries.");
		}
		return keys;
	}

	private static JWK toJwk(String kid, String base64Key) {
		byte[] material;
		try {
			material = Base64.getDecoder().decode(base64Key);
		}
		catch (IllegalArgumentException ex) {
			// Deliberately not chaining `ex`: its message quotes the offending input, which
			// is part of the key. The kid is enough to find the broken entry.
			throw new IllegalStateException(
					"JWT_SIGNING_KEYS key id '" + kid + "' is not valid base64.");
		}

		if (material.length < MIN_KEY_BYTES) {
			throw new IllegalStateException("JWT_SIGNING_KEYS key id '" + kid + "' decodes to "
					+ material.length + " bytes; at least " + MIN_KEY_BYTES
					+ " are required. Generate one with: openssl rand -base64 32");
		}

		// The algorithm is set on the JWK because both the encoder's and the decoder's key
		// matchers filter on it: a key without `alg` would still match, but one tagged with a
		// different algorithm would silently stop being selected.
		return new OctetSequenceKey.Builder(material)
				.keyID(kid)
				.algorithm(JWSAlgorithm.HS256)
				.build();
	}

	/** The keys, for the encoder and the decoder to select from by {@code kid}. */
	public JWKSource<SecurityContext> jwkSource() {
		return jwkSource;
	}

	/** The {@code kid} new tokens are signed with. */
	public String currentKid() {
		return currentKid;
	}

	/** How many keys are accepted. For tests and the startup log, never for a decision. */
	public int size() {
		return jwkSet.getKeys().size();
	}

	/**
	 * Never prints key material.
	 *
	 * <p>{@link JWKSet#toString()} serialises the whole set, including every {@code k} value,
	 * so the inherited implementation would print every signing key.
	 */
	@Override
	public String toString() {
		return "SigningKeyRing[keys=" + size() + ", currentKid=" + currentKid + "]";
	}
}
