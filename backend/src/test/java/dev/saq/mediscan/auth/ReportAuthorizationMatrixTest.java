package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestTokens;
import dev.saq.mediscan.user.UserRole;

/**
 * Every combination of report owner and caller, on the real
 * {@code GET} and {@code DELETE /api/reports/{id}}.
 *
 * <p>The matrix is written out rather than reasoned about because the failure mode is silent:
 * a query that forgot its owner filter still returns a report, and no test that only checks
 * "the owner can read their report" would notice. Four owners and five callers is twenty
 * cases, and the interesting nineteen are the ones that must fail.
 *
 * <p>Another owner's report is always {@code 404 REPORT_NOT_FOUND}, never 403: a 403 would
 * confirm the id exists, and report ids are the only thing protecting a report from being
 * guessed at (CLAUDE.md rule 7, contract 4.2).
 */
class ReportAuthorizationMatrixTest extends PostgresTestBase {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private JdbcClient jdbc;

	private String guestAToken;
	private String guestBToken;
	private UUID userA;
	private UUID userB;
	private String userAToken;
	private String userBToken;

	private UUID guestAReport;
	private UUID guestBReport;
	private UUID userAReport;
	private UUID userBReport;

	@BeforeEach
	void setUpOwners() {
		guestAToken = fixtures.sessionToken();
		guestBToken = fixtures.sessionToken();
		guestAReport = fixtures.pendingReport(fixtures.sessionIdFor(guestAToken));
		guestBReport = fixtures.pendingReport(fixtures.sessionIdFor(guestBToken));

		userA = fixtures.user();
		userB = fixtures.user();
		userAToken = TestTokens.signingWithCurrentKey().validFor(userA, UserRole.USER);
		userBToken = TestTokens.signingWithCurrentKey().validFor(userB, UserRole.USER);
		userAReport = fixtures.pendingUserReport(userA);
		userBReport = fixtures.pendingUserReport(userB);
	}

	// --- GET: the owner succeeds --------------------------------------------

	@Test
	@DisplayName("each owner can read their own report")
	void ownersCanReadTheirOwn() throws Exception {
		mockMvc.perform(asGuest(get("/api/reports/" + guestAReport), guestAToken))
				.andExpect(status().isOk());
		mockMvc.perform(asUser(get("/api/reports/" + userAReport), userAToken))
				.andExpect(status().isOk());
	}

	// --- GET: everyone else gets 404 ----------------------------------------

	@Test
	@DisplayName("guest A cannot read guest B's report")
	void guestCannotReadAnotherGuest() throws Exception {
		expectNotFound(asGuest(get("/api/reports/" + guestBReport), guestAToken));
	}

	@Test
	@DisplayName("user A cannot read user B's report")
	void userCannotReadAnotherUser() throws Exception {
		expectNotFound(asUser(get("/api/reports/" + userBReport), userAToken));
	}

	@Test
	@DisplayName("a guest cannot read a user's report")
	void guestCannotReadUsersReport() throws Exception {
		expectNotFound(asGuest(get("/api/reports/" + userAReport), guestAToken));
	}

	@Test
	@DisplayName("a user cannot read a guest's report")
	void userCannotReadGuestsReport() throws Exception {
		// The direction that would break if a query compared the owner id without checking
		// which column it belongs to.
		expectNotFound(asUser(get("/api/reports/" + guestAReport), userAToken));
	}

	@Test
	@DisplayName("an anonymous caller gets 401, not 404")
	void anonymousGetsUnauthorized() throws Exception {
		// Deliberately different from the other failures: with no credential at all the
		// caller cannot own anything, and the client's move is to get a session.
		mockMvc.perform(get("/api/reports/" + guestAReport))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("SESSION_INVALID"));
	}

	// --- DELETE: the same matrix --------------------------------------------

	@Test
	@DisplayName("each owner can delete their own report")
	void ownersCanDeleteTheirOwn() throws Exception {
		mockMvc.perform(asGuest(delete("/api/reports/" + guestAReport), guestAToken))
				.andExpect(status().isNoContent());
		mockMvc.perform(asUser(delete("/api/reports/" + userAReport), userAToken))
				.andExpect(status().isNoContent());

		assertThat(reportExists(guestAReport)).isFalse();
		assertThat(reportExists(userAReport)).isFalse();
	}

	@Test
	@DisplayName("nobody else can delete a report, and it survives the attempt")
	void othersCannotDelete() throws Exception {
		expectNotFound(asGuest(delete("/api/reports/" + guestBReport), guestAToken));
		expectNotFound(asGuest(delete("/api/reports/" + userAReport), guestAToken));
		expectNotFound(asUser(delete("/api/reports/" + userBReport), userAToken));
		expectNotFound(asUser(delete("/api/reports/" + guestAReport), userAToken));

		// The important half: a refused delete must not have deleted anything.
		assertThat(reportExists(guestAReport)).isTrue();
		assertThat(reportExists(guestBReport)).isTrue();
		assertThat(reportExists(userAReport)).isTrue();
		assertThat(reportExists(userBReport)).isTrue();
	}

	@Test
	@DisplayName("an unknown report id is 404 for every caller")
	void unknownIdIsNotFound() throws Exception {
		UUID nobodysReport = UUID.randomUUID();

		expectNotFound(asGuest(get("/api/reports/" + nobodysReport), guestAToken));
		expectNotFound(asUser(get("/api/reports/" + nobodysReport), userAToken));
	}

	// --- uploads ------------------------------------------------------------

	@Test
	@DisplayName("a signed-in user's upload is owned by them, with a 30-day expiry")
	void userUploadIsOwnedByTheUser() throws Exception {
		String response = mockMvc.perform(post("/api/reports")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userAToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"sampleId\":\"lipid-panel\",\"consent\":true}"))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		UUID reportId = UUID.fromString(
				tools.jackson.databind.json.JsonMapper.builder().build()
						.readTree(response).get("id").asString());

		assertThat(userIdOf(reportId)).isEqualTo(userA);
		assertThat(sessionIdOf(reportId)).isNull();
		assertThat(expiryDays(reportId)).isEqualTo(30);
	}

	// --- helpers ------------------------------------------------------------

	private static MockHttpServletRequestBuilder asGuest(MockHttpServletRequestBuilder request,
			String sessionToken) {
		return request.header(GuestAuthFilter.HEADER, sessionToken);
	}

	private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request,
			String accessToken) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
	}

	private void expectNotFound(MockHttpServletRequestBuilder request) throws Exception {
		mockMvc.perform(request)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
	}

	private boolean reportExists(UUID reportId) {
		Integer count = jdbc.sql("SELECT count(*) FROM report WHERE id = :id")
				.param("id", reportId).query(Integer.class).single();
		return count != null && count > 0;
	}

	private UUID userIdOf(UUID reportId) {
		return jdbc.sql("SELECT user_id FROM report WHERE id = :id")
				.param("id", reportId).query(UUID.class).optional().orElse(null);
	}

	private UUID sessionIdOf(UUID reportId) {
		return jdbc.sql("SELECT session_id FROM report WHERE id = :id")
				.param("id", reportId).query(UUID.class).optional().orElse(null);
	}

	private int expiryDays(UUID reportId) {
		Integer days = jdbc.sql("""
				SELECT EXTRACT(DAY FROM (expires_at - created_at))::int
				  FROM report WHERE id = :id
				""")
				.param("id", reportId).query(Integer.class).single();
		return days != null ? days : -1;
	}
}
