package dev.saq.mediscan.auth;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;

import dev.saq.mediscan.user.UserRole;

/**
 * The decoder that validates the app's own access tokens, and the converter that turns one
 * into an authentication.
 *
 * <p>This is the <strong>only</strong> {@link JwtDecoder} bean in the application. Google's ID
 * token decoder is built inside {@link GoogleIdTokenVerifier} and is deliberately not exposed,
 * because a second bean of this type would make both Boot's autoconfiguration and
 * {@code oauth2ResourceServer}'s decoder lookup ambiguous (LLD 5, D9).
 *
 * <p>Two things here are not the obvious spelling, both verified against Spring Security 7.1.1
 * rather than assumed:
 *
 * <ul>
 * <li><strong>The HS256 key selector</strong> goes in through
 * {@code jwtProcessorCustomizer}. {@code jwsAlgorithm(...)} only accepts
 * {@code SignatureAlgorithm}, which holds the asymmetric algorithms; HS256 lives in
 * {@code MacAlgorithm} and is not assignable to it. The customizer runs after the builder has
 * installed its default RS256 selector, so it replaces it, while Spring's no-op claims
 * verifier and type verifier are left in place (D1).</li>
 * <li><strong>The validator list is assembled by hand</strong> rather than through
 * {@code JwtValidators.createDefaultWithValidators}. That helper re-adds a
 * {@code JwtTimestampValidator} unless it finds one in the list by type, and that class is
 * {@code final}, so {@link ExpiryValidator} can never satisfy the check. The default would
 * then run alongside ours and also report an expired token as plain {@code invalid_token},
 * defeating the point of the custom code (D3).</li>
 * </ul>
 */
@Configuration
public class AccessTokenDecoderConfig {

	/**
	 * Validates signature, {@code kid}, {@code typ}, expiry, issuer, audience, and the two
	 * claims the application cannot work without.
	 */
	@Bean
	JwtDecoder accessTokenDecoder(SigningKeyRing keyRing, AuthProperties properties, Clock clock) {
		JWKSource<SecurityContext> jwkSource = keyRing.jwkSource();

		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource)
				.jwtProcessorCustomizer(processor -> processor.setJWSKeySelector(
						new JWSVerificationKeySelector<>(JWSAlgorithm.HS256, jwkSource)))
				.build();

		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
				// typ must be JWT or absent; the issuer sets it explicitly.
				JwtTypeValidator.jwt(),
				new RequiredKeyIdValidator(),
				new ExpiryValidator(properties.jwt().clockSkew(), clock),
				new JwtIssuerValidator(properties.jwt().issuer()),
				new JwtAudienceValidator(properties.jwt().audience()),
				requiredSubject(),
				requiredRole())));

		return decoder;
	}

	/**
	 * Maps the {@code role} claim to an authority and makes the user id the principal name.
	 *
	 * <p>Not the framework default, which reads {@code scope} and {@code scp}. These tokens
	 * have no scopes - a user either is or is not an admin.
	 */
	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setPrincipalClaimName(JwtClaimNames.SUB);
		converter.setJwtGrantedAuthoritiesConverter(new RoleClaimAuthoritiesConverter());
		return converter;
	}

	/**
	 * {@code sub} must be a UUID.
	 *
	 * <p>Checked here rather than where it is used, so a token whose {@code sub} could not be
	 * an owner id is rejected as a token instead of blowing up later in a parse. The predicate
	 * null-checks because an absent claim reaches it as {@code null}.
	 */
	private static OAuth2TokenValidator<Jwt> requiredSubject() {
		return new JwtClaimValidator<String>(JwtClaimNames.SUB,
				subject -> subject != null && isUuid(subject));
	}

	/** {@code role} must be one this application knows, so authority mapping cannot fail. */
	private static OAuth2TokenValidator<Jwt> requiredRole() {
		return new JwtClaimValidator<String>("role", RoleClaimAuthoritiesConverter::isKnownRole);
	}

	private static boolean isUuid(String value) {
		try {
			UUID.fromString(value);
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/** Turns the {@code role} claim into exactly one authority. */
	static final class RoleClaimAuthoritiesConverter
			implements Converter<Jwt, Collection<GrantedAuthority>> {

		@Override
		public Collection<GrantedAuthority> convert(Jwt jwt) {
			String role = jwt.getClaimAsString("role");
			// The decoder's requiredRole() validator has already rejected anything else, so
			// this cannot be reached with an unknown role.
			return List.of(new SimpleGrantedAuthority(UserRole.valueOf(role).authority()));
		}

		static boolean isKnownRole(String role) {
			if (role == null) {
				return false;
			}
			for (UserRole known : UserRole.values()) {
				if (known.name().equals(role)) {
					return true;
				}
			}
			return false;
		}
	}
}
