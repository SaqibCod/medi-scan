package dev.saq.mediscan.trends;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.saq.mediscan.config.CurrentOwner;
import dev.saq.mediscan.session.OwnerRef;

/**
 * {@code GET /api/trends} and {@code GET /api/trends/markers} (contract section 5, LLD 6).
 *
 * <p>Both are signed-in only, enforced in the filter chain by {@code hasAnyRole('USER','ADMIN')}
 * - a guest gets {@code 401 AUTH_REQUIRED} and never reaches these methods.
 */
@RestController
@RequestMapping("/api/trends")
public class TrendsController {

	private final TrendsService service;

	public TrendsController(TrendsService service) {
		this.service = service;
	}

	@GetMapping("/markers")
	public List<TrendMarker> markers(@CurrentOwner OwnerRef owner) {
		return service.markers(owner);
	}

	@GetMapping
	public TrendResponse trend(@CurrentOwner OwnerRef owner, @RequestParam(required = false) String marker) {
		return service.trend(owner, marker);
	}
}
