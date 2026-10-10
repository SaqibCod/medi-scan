package dev.saq.mediscan.support;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A stand-in admin endpoint, in test sources only.
 *
 * <p>Phase 6a puts the {@code /api/admin/**} authorization rule in place but ships no admin
 * endpoint - those arrive with phase 6b. Without something mapped under that path the rule
 * could only be tested as "returns 404", which would pass just as well if the rule were
 * missing entirely.
 *
 * <p>Carries {@code @PreAuthorize} as well, because the real admin controllers will. That
 * makes this the test for {@code @EnableMethodSecurity} actually being switched on: Spring
 * Boot auto-configures no method security, so without the annotation on
 * {@code SecurityConfig} this annotation is inert (LLD 6.4, D8).
 */
@RestController
class TestAdminController {

	@GetMapping("/api/admin/support/ping")
	@PreAuthorize("hasRole('ADMIN')")
	Map<String, String> ping() {
		return Map.of("status", "ok");
	}

	/**
	 * Deliberately <em>without</em> {@code @PreAuthorize}, under the same path prefix.
	 *
	 * <p>Proves the filter chain's matcher protects the path on its own, so a new admin
	 * endpoint is safe even if whoever adds it forgets the annotation.
	 */
	@GetMapping("/api/admin/support/unannotated")
	Map<String, String> unannotated() {
		return Map.of("status", "ok");
	}

	/**
	 * {@code @PreAuthorize} on a path the filter chain lets any authenticated caller reach.
	 *
	 * <p>This is the only way to test method security in isolation. On an
	 * {@code /api/admin/**} path the chain's matcher would deny a non-admin anyway, so the
	 * test would pass whether or not {@code @EnableMethodSecurity} were present. Here the
	 * matcher permits the request and the annotation is the only thing that can refuse it
	 * (LLD 6.4, D8).
	 */
	@GetMapping("/api/reports/support/admin-only")
	@PreAuthorize("hasRole('ADMIN')")
	Map<String, String> adminOnlyOnAnOpenPath() {
		return Map.of("status", "ok");
	}
}
