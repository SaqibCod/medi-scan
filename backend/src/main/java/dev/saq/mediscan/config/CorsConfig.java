package dev.saq.mediscan.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS exactly as specified in {@code docs/api-contract.md} section 1.4.
 *
 * <p>The frontend is on Vercel and the API on EC2, so every browser call is cross-origin and
 * this configuration is what makes the app work at all.
 */
@Configuration
public class CorsConfig {

	@Bean
	CorsConfigurationSource corsConfigurationSource(MediScanProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();

		// One exact origin, never a wildcard: with no wildcard there is no way for an
		// arbitrary site to read a response, even though no credentials are involved.
		cors.setAllowedOrigins(List.of(properties.allowedOrigin()));

		cors.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));

		cors.setAllowedHeaders(List.of(
				HttpHeaders.CONTENT_TYPE,
				"X-Session-Token",
				HttpHeaders.AUTHORIZATION,
				HttpHeaders.ACCEPT));

		// Without exposing these, the browser hides them from JavaScript: X-Request-Id is
		// shown in the error UI, Retry-After drives the retry delay, Location carries the new
		// report's URL, and WWW-Authenticate distinguishes bearer failures.
		cors.setExposedHeaders(List.of(
				RequestIdFilter.HEADER,
				HttpHeaders.RETRY_AFTER,
				HttpHeaders.LOCATION,
				HttpHeaders.WWW_AUTHENTICATE));

		// No cookies carry credentials - tokens travel in headers - so credentialed mode is
		// deliberately off. This is also why CSRF protection is disabled (see SecurityConfig).
		cors.setAllowCredentials(false);

		cors.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}
}
