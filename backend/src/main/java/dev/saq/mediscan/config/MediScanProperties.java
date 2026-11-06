package dev.saq.mediscan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The application's own configuration, bound from {@code mediscan.*}.
 *
 * <p>Values come from environment variables through {@code application.yml}; nothing here has
 * a secret in it. Typed as records so a missing or malformed value fails at startup rather
 * than at the first request.
 *
 * @param allowedOrigin the single permitted CORS origin, from {@code ALLOWED_ORIGIN}
 */
@ConfigurationProperties("mediscan")
public record MediScanProperties(
		String allowedOrigin,
		Session session,
		RateLimit ratelimit) {

	/**
	 * @param ttlHours how long a guest session, and the reports it owns, live for
	 */
	public record Session(int ttlHours) {
	}

	/**
	 * @param sessionsPerHour session creations allowed per IP per hour (contract section 1.6)
	 */
	public record RateLimit(int sessionsPerHour) {
	}
}
