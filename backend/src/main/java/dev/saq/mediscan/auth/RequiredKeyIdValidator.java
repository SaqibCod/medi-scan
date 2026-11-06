package dev.saq.mediscan.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Requires a {@code kid} header on every access token.
 *
 * <p>This closes a real hole rather than enforcing a convention. Nimbus builds its key matcher
 * from the JWS header, and a {@code kid} of {@code null} means *unconstrained*, not "has no
 * key id" - so every key in the ring matches, and {@code DefaultJWTProcessor} then tries each
 * candidate in turn until one verifies. A token with no {@code kid} that was signed with any
 * listed key would therefore be accepted (LLD 6.3, D2).
 *
 * <p>An <em>unknown</em> {@code kid} needs no help: the matcher constrains the key id, nothing
 * matches, and Nimbus rejects the token before any validator runs.
 *
 * <p>Runs after signature verification, which is harmless: the answer is
 * {@code 401 TOKEN_INVALID} either way, and the signature check has already proved the token
 * was signed with one of this application's keys.
 */
public class RequiredKeyIdValidator implements OAuth2TokenValidator<Jwt> {

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		Object keyId = jwt.getHeaders().get(JoseHeaderNames.KID);

		if (keyId instanceof String kid && !kid.isBlank()) {
			return OAuth2TokenValidatorResult.success();
		}
		return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
				"the kid header is required", null));
	}
}
