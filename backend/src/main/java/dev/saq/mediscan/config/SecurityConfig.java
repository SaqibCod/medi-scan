package dev.saq.mediscan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

import dev.saq.mediscan.ratelimit.IpRateLimiter;
import dev.saq.mediscan.ratelimit.UploadRateLimitFilter;
import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.session.SessionService;
import dev.saq.mediscan.stats.StatsRecorder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The application's one security filter chain.
 *
 * <p>One chain, not several, so the full set of authorization rules can be read in a single
 * place. Everything is stateless: no HTTP session is ever created, and credentials arrive in
 * headers on every request.
 *
 * <p>{@code @EnableMethodSecurity} is load-bearing, not decoration. Spring Boot 4.1 ships no
 * method-security autoconfiguration, so without it every {@code @PreAuthorize} in the
 * codebase is silently inert - the annotation would be there, the check would not happen, and
 * nothing would fail (LLD 6.4, D8). {@code AdminRouteTest} asserts that it actually denies.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			CorsConfigurationSource corsConfigurationSource,
			SessionService sessionService,
			ProblemAuthenticationEntryPoint authenticationEntryPoint,
			ProblemAccessDeniedHandler accessDeniedHandler,
			IpRateLimiter ipRateLimiter,
			JsonMapper jsonMapper,
			StatsRecorder statsRecorder,
			JwtDecoder accessTokenDecoder,
			JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

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
						// forgets the annotation. Phase 6b adds the endpoints.
						.requestMatchers("/api/admin/**").hasRole("ADMIN")

						// Signed-in only, and deliberately not `.authenticated()`: guests are
						// authenticated too - GuestAuthFilter gives them ROLE_GUEST - so
						// `.authenticated()` would let a guest through to a handler that has
						// no user to work with. Requiring a role denies them at the
						// authorization filter, and ProblemAccessDeniedHandler turns a guest
						// denial into 401 AUTH_REQUIRED (LLD 6.4, D6).
						.requestMatchers("/api/me/**", "/api/trends/**").hasAnyRole("USER", "ADMIN")

						// Reports accept either owner type: a guest session or a bearer
						// token. Which reports a caller may see is an ownership question,
						// enforced in the repository by OwnerRef, not here.
						.requestMatchers("/api/reports/**").authenticated()

						.anyRequest().authenticated())

				// Bearer tokens: the app's own access tokens, validated by the single
				// JwtDecoder bean. The entry point is passed here as well as to
				// exceptionHandling below, because OAuth2ResourceServerConfigurer sets its
				// own on the bearer filter - defaulting to BearerTokenAuthenticationEntryPoint,
				// which answers with no `code` field and an error_description header this API
				// does not want to emit (LLD 6.4, D7).
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt
								.decoder(accessTokenDecoder)
								.jwtAuthenticationConverter(jwtAuthenticationConverter))
						.authenticationEntryPoint(authenticationEntryPoint))

				// Both render RFC 9457 Problem Details. Without them Spring Security returns
				// an empty body and the client has no `code` to switch on. Setting them
				// explicitly also wins over the resource server's own registration, which
				// targets a matcher broad enough to cover guest requests too.
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))

				// Resolves X-Session-Token into a guest principal, and runs *after* the
				// bearer filter.
				//
				// Anchored on BearerTokenAuthenticationFilter rather than
				// UsernamePasswordAuthenticationFilter: FilterOrderRegistration sorts the
				// bearer filter after the username/password one, so the phase 1 anchor would
				// put the guest filter first - the opposite of what rule 16 needs (D5).
				//
				// A request carrying Authorization never reaches this filter anyway: the
				// bearer filter either authenticates it or calls the entry point and returns
				// without continuing the chain. GuestAuthFilter's own guard is the third
				// layer of the same guarantee.
				.addFilterAfter(new GuestAuthFilter(sessionService),
						BearerTokenAuthenticationFilter.class)

				// The per-caller upload limit, after the guest filter so an unauthenticated
				// request is refused before it can spend a token (LLD 6.1). It has to run
				// before the controller, because a multipart body is up to 10 MB and letting
				// Spring spool that to disk before rejecting the request is most of what the
				// limit exists to prevent.
				.addFilterAfter(new UploadRateLimitFilter(ipRateLimiter, jsonMapper, statsRecorder),
						GuestAuthFilter.class);

		return http.build();
	}
}
