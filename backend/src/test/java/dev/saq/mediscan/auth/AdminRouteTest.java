package dev.saq.mediscan.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestTokens;
import dev.saq.mediscan.user.UserRole;

/**
 * Access to {@code /api/admin/**}, through the test-only controller in
 * {@code dev.saq.mediscan.support}.
 *
 * <p>Phase 6a ships the rule but no admin endpoint, so without that stand-in controller these
 * cases could only assert "404", which would look identical if the rule were missing. See
 * {@code TestAdminController}.
 *
 * <p>The three answers are deliberately different, and the difference is the point: an
 * anonymous or guest caller gets {@code 401 AUTH_REQUIRED} because signing in is what they
 * need to do, while a signed-in non-admin gets {@code 403 FORBIDDEN} because nothing they do
 * with their credential will help (LLD 6.4).
 */
class AdminRouteTest extends PostgresTestBase {

	private static final String ANNOTATED = "/api/admin/support/ping";
	private static final String UNANNOTATED = "/api/admin/support/unannotated";

	/** Not under {@code /api/admin/**}: the matcher permits it, so only the annotation denies. */
	private static final String ADMIN_ONLY_OPEN_PATH = "/api/reports/support/admin-only";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReportFixtures fixtures;

	@Test
	@DisplayName("an anonymous caller gets AUTH_REQUIRED")
	void anonymousIsAuthRequired() throws Exception {
		mockMvc.perform(get(ANNOTATED))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	@DisplayName("a guest gets AUTH_REQUIRED, not FORBIDDEN")
	void guestIsAuthRequired() throws Exception {
		// A guest is authenticated, so Spring routes this to the access-denied handler. The
		// honest answer is still "sign in": they have no account, and 403 would imply their
		// credential was rejected.
		mockMvc.perform(get(ANNOTATED).header(GuestAuthFilter.HEADER, fixtures.sessionToken()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	@DisplayName("a signed-in USER gets FORBIDDEN")
	void userIsForbidden() throws Exception {
		UUID userId = fixtures.user();
		String token = TestTokens.signingWithCurrentKey().validFor(userId, UserRole.USER);

		mockMvc.perform(get(ANNOTATED).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("an ADMIN gets through")
	void adminIsAllowed() throws Exception {
		UUID adminId = fixtures.adminUser();
		String token = TestTokens.signingWithCurrentKey().validFor(adminId, UserRole.ADMIN);

		mockMvc.perform(get(ANNOTATED).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"));
	}

	@Test
	@DisplayName("the role claim decides, not the stored role")
	void roleComesFromTheToken() throws Exception {
		// An ADMIN account presenting a USER-role token is a USER for that request. The token
		// is the credential; app_user.role is only a cache of the config
		// (docs/dataflow.md section 3.2).
		UUID adminId = fixtures.adminUser();
		String userToken = TestTokens.signingWithCurrentKey().validFor(adminId, UserRole.USER);

		mockMvc.perform(get(ANNOTATED).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("the path is protected even without @PreAuthorize on the controller")
	void filterChainProtectsUnannotatedEndpoint() throws Exception {
		// Belt and braces: the matcher in SecurityConfig means a new admin endpoint is safe
		// even if whoever adds it forgets the annotation.
		UUID userId = fixtures.user();
		String token = TestTokens.signingWithCurrentKey().validFor(userId, UserRole.USER);

		mockMvc.perform(get(UNANNOTATED).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("@PreAuthorize denies on a path the filter chain allows")
	void methodSecurityIsEnabled() throws Exception {
		// The real test of @EnableMethodSecurity. Spring Boot auto-configures no method
		// security, so without that annotation on SecurityConfig every @PreAuthorize is
		// inert. This path is under /api/reports/**, which the chain opens to any
		// authenticated caller, so the only thing that can refuse a USER here is the
		// annotation itself (D8).
		UUID userId = fixtures.user();
		String userToken = TestTokens.signingWithCurrentKey().validFor(userId, UserRole.USER);

		mockMvc.perform(get(ADMIN_ONLY_OPEN_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("@PreAuthorize still lets an admin through on that same path")
	void methodSecurityAllowsAdmin() throws Exception {
		// The other half: a rule that denied everyone would also pass the test above.
		UUID adminId = fixtures.adminUser();
		String adminToken = TestTokens.signingWithCurrentKey().validFor(adminId, UserRole.ADMIN);

		mockMvc.perform(get(ADMIN_ONLY_OPEN_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"));
	}
}
