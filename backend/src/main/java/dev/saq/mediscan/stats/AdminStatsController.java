package dev.saq.mediscan.stats;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/admin/stats} (contract section 6, LLD 7).
 *
 * <p>{@code @PreAuthorize} here is belt and braces: the filter chain already requires
 * {@code hasRole('ADMIN')} on all of {@code /api/admin/**}, so this endpoint stays protected
 * even if the chain's matcher is ever narrowed, matching the pattern {@code SecurityConfig}
 * documents for every admin route.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminStatsController {

	private final AdminStatsService service;

	public AdminStatsController(AdminStatsService service) {
		this.service = service;
	}

	@GetMapping("/stats")
	@PreAuthorize("hasRole('ADMIN')")
	public AdminStats stats(@RequestParam(required = false) Integer days) {
		return service.stats(days);
	}
}
