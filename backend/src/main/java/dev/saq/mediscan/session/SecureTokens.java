package dev.saq.mediscan.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Makes and hashes the opaque tokens Medi-Scan hands out.
 *
 * <p>Guest session tokens and refresh tokens are the same kind of thing: 32 bytes of
 * {@link SecureRandom} output, returned to the client exactly once, and stored only as a
 * SHA-256 hash (CLAUDE.md rule 14). One helper for both rather than two implementations, so
 * there is a single place where the length, the encoding and the digest are decided - a second
 * copy is how a weaker token or a different hash shape gets introduced by accident (LLD 7.6).
 *
 * <p>A plain hash rather than a password hash, deliberately: the input is 32 random bytes, so
 * there is no dictionary to attack and nothing for a slow KDF to protect against. Hashing is
 * here so that a leaked database cannot be replayed as a set of credentials.
 */
public final class SecureTokens {

	/** 32 bytes, which is 43 characters once base64url encoded without padding. */
	private static final int TOKEN_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private SecureTokens() {
	}

	/**
	 * A fresh token. The caller must return it to the client and keep only {@link #hash}.
	 *
	 * <p>Never log the result.
	 */
	public static String newToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}

	/** SHA-256 of a raw token, hex encoded: 64 characters, matching the {@code varchar(64)} columns. */
	public static String hash(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is required of every JVM, so this cannot happen.
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}
}
