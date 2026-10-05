package dev.saq.mediscan.session;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import dev.saq.mediscan.ratelimit.IpRateLimiter;

/**
 * {@code POST /api/sessions} - starts an anonymous session (contract section 2.2).
 *
 * <p>The client calls this lazily, on the first guest action that needs a session, not on page
 * load. Browsing the biomarker pages creates nothing.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

	private final SessionService sessionService;
	private final IpRateLimiter rateLimiter;

	public SessionController(SessionService sessionService, IpRateLimiter rateLimiter) {
		this.sessionService = sessionService;
		this.rateLimiter = rateLimiter;
	}

	/**
	 * Takes no body and no credential. Returns {@code 201} with the raw token, which is the
	 * only time it leaves the server.
	 *
	 * <p>The rate limit is checked before the session is created, so a blocked request writes
	 * nothing to the database.
	 */
	@PostMapping
	public ResponseEntity<SessionResponse> create(HttpServletRequest request) {
		rateLimiter.checkSessionCreation(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.create());
	}
}
