package dev.saq.mediscan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.session.SessionService;

/**
 * The application's one security filter chain.
 *
 * <p>One chain, not several, so the full set of authorization rules can be read in a single
 * place. Everything is stateless: no HTTP session is ever created, and credentials arrive in
 * headers on every request.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			CorsConfigurationSource corsConfigurationSource,
			SessionService sessionService,
			ProblemAuthenticationEntryPoint authenticationEntryPoint,
			ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {

		http
				.cors(cors -> cors.configurationSource(corsConfigurationSource))

				// CSRF protection is disabled because no cookie carries a credential.
				//
				// CSRF attacks work by making the browser attach an ambient credential - a
				// cookie - to a request the user did not intend. Medi-Scan sends its
				// credentials as explicit headers (X-Session-Token, Authorization), which a
				// cross-site form or image tag cannot set, and CORS is restricted to one
				// origin for anything scripted. There is nothing for CSRF to forge.
				//
				// This comment is load-bearing: if a credential is ever moved into a cookie,
				// CSRF protection has to come back on. See docs/plan.md section 4.9.
				.csrf(csrf -> csrf.disable())

				// Stateless: never create or use an HTTP session. A server-side session would
				// be another thing to replicate, expire and leak.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

				// Spring Security's own defaults for these would fight the API contract.
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())

				.authorizeHttpRequests(authorize -> authorize
						// CORS preflight carries no credentials, so it must be reachable or
						// every cross-origin call fails before it is sent.
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

						// Public: nothing here needs a credential. Guests must be able to
						// reach every core feature without signing in (CLAUDE.md rule 11).
						.requestMatchers(HttpMethod.POST, "/api/sessions").permitAll()
						.requestMatchers("/api/samples", "/api/samples/**").permitAll()
						.requestMatchers("/api/auth/**").permitAll()
						.requestMatchers("/actuator/health").permitAll()

						// Admin needs the role in the chain as well as @PreAuthorize on the
						// controller, so a new admin endpoint is protected even if someone
						// forgets the annotation. Phase 6 adds the endpoints.
						.requestMatchers("/api/admin/**").hasRole("ADMIN")

						// Reports accept either owner type: a guest session now, a bearer
						// token from phase 6. Which reports a caller may see is an ownership
						// question, enforced in the repository by OwnerRef, not here.
						.requestMatchers("/api/reports/**").authenticated()

						.anyRequest().authenticated())

				// Both render RFC 9457 Problem Details. Without them Spring Security returns
				// an empty body and the client has no `code` to switch on.
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))

				// Resolves X-Session-Token into a guest principal. Placed where the standard
				// authentication filter would sit, so it runs before authorization.
				.addFilterBefore(new GuestAuthFilter(sessionService),
						UsernamePasswordAuthenticationFilter.class);

		// ---------------------------------------------------------------------
		// PHASE 6: bearer tokens go here.
		//
		// Add spring-boot-starter-oauth2-resource-server, then:
		//
		//     .oauth2ResourceServer(oauth2 -> oauth2
		//             .jwt(jwt -> jwt.decoder(jwtDecoder))
		//             .authenticationEntryPoint(authenticationEntryPoint))
		//
		// The decoder is a NimbusJwtDecoder over a JWKSource holding one
		// OctetSequenceKey per entry in JWT_SIGNING_KEYS, selected by the token's
		// `kid`, and validating issuer and audience. No hand-written parsing or
		// signature checks (CLAUDE.md, "Auth library code").
		//
		// It must run *before* GuestAuthFilter in the chain, and GuestAuthFilter
		// already declines to act when an Authorization header is present, so a bad
		// bearer token fails outright instead of degrading to guest access
		// (CLAUDE.md rule 16).
		// ---------------------------------------------------------------------

		return http.build();
	}
}
