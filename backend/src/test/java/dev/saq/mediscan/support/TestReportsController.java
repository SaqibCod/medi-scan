package dev.saq.mediscan.support;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.saq.mediscan.config.CurrentOwner;
import dev.saq.mediscan.session.OwnerRef;

/**
 * A probe for what the filter chain and the owner resolver handed the handler.
 *
 * <p>Lives in the test sources only, and is picked up by component scan because the tests sit
 * under the same base package. It exists so the authentication path can be tested for what it
 * is supposed to do - work out who is calling and hand the handler a usable owner reference -
 * and not merely for "did not return 401".
 *
 * <p>Takes a {@code @CurrentOwner OwnerRef}, exactly as the real endpoints do, so it reports
 * the resolver's answer rather than a reconstruction of it. That is what makes it able to
 * tell a guest-owned request from a user-owned one, which is how rule 16 - a bearer token
 * never degrading to guest access - is checked end to end.
 *
 * <p>Path is under {@code /api/reports} so it is governed by the same authorization rule as
 * the real endpoints, but on a two-segment {@code support/} path so it cannot be mistaken for
 * - or shadow - the real {@code GET /api/reports/{id}} that phase 2 added.
 */
@RestController
class TestReportsController {

	@GetMapping("/api/reports/support/whoami")
	Map<String, String> whoami(@CurrentOwner OwnerRef owner) {
		return Map.of(
				"ownerType", owner instanceof OwnerRef.Guest ? "GUEST" : "USER",
				"ownerId", owner.id().toString());
	}
}
