package dev.saq.mediscan.support;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.saq.mediscan.session.GuestPrincipal;
import dev.saq.mediscan.session.OwnerRef;

/**
 * A probe for what the guest filter handed the handler.
 *
 * <p>Lives in the test sources only, and is picked up by component scan because the tests sit
 * under the same base package. It exists so the guest filter can be tested for what it is
 * supposed to do - authenticate a caller and hand the handler a usable owner reference - and
 * not merely for "did not return 401".
 *
 * <p>Path is under {@code /api/reports} so it is governed by the same authorization rule as
 * the real endpoints, but on a two-segment {@code support/} path so it cannot be mistaken for
 * - or shadow - the real {@code GET /api/reports/{id}} that phase 2 added.
 */
@RestController
class TestReportsController {

	@GetMapping("/api/reports/support/whoami")
	Map<String, String> whoami(@AuthenticationPrincipal GuestPrincipal principal) {
		OwnerRef owner = principal.toOwnerRef();
		return Map.of(
				"ownerType", owner instanceof OwnerRef.Guest ? "GUEST" : "USER",
				"ownerId", owner.id().toString());
	}
}
