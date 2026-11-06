package dev.saq.mediscan.upload;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/samples} (contract section 3).
 *
 * <p>Unauthenticated, by design: the sample list is how a first-time visitor sees what the
 * app does, and requiring even a guest session for it would mean creating one on page load -
 * which {@code CLAUDE.md} rules out ("Guest sessions: create them lazily"). The permit rule
 * is in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/samples")
public class SampleController {

	/** Contract section 3: the client may cache this for the whole session. */
	private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

	private final SampleCatalog catalog;

	public SampleController(SampleCatalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	public ResponseEntity<List<SampleResponse>> list() {
		List<SampleResponse> body = catalog.all().stream()
				.map(SampleResponse::from)
				.toList();

		return ResponseEntity.ok().cacheControl(CACHE).body(body);
	}
}
