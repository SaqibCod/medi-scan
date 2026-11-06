package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestGoogleIssuer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three {@code /api/auth/*} endpoints and {@code /api/me}, end to end.
 *
 * <p>Through MockMvc rather than against the services, because the parts worth checking here
 * are the ones only the full stack shows: the JSON shape the contract promises, the status
 * codes, and that a claimed report really is reachable with the new token and no longer with
 * the old one.
 */
@Import(TestGoogleVerifierConfig.class)
class AuthEndpointsTest extends PostgresTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TestGoogleIssuer google;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private JdbcClient jdbc;

	// --- sign-in -------------------------------------------------------------

	@Test
	@DisplayName("a first sign-in creates the account and returns both tokens")
	void firstSignInCreatesAccount() throws Exception {
		String sub = uniqueSub();

		JsonNode body = json(signIn(google.validToken(sub, "Priya Raman"), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.newUser").value(true))
				.andExpect(jsonPath("$.claimedReportCount").value(0)));

		assertThat(body.get("accessToken").asString()).isNotBlank();
		assertThat(body.get("refreshToken").asString()).hasSize(43);
		assertThat(Instant.parse(body.get("accessTokenExpiresAt").asString()))
				.isAfter(Instant.now());
		assertThat(body.get("user").get("displayName").asString()).isEqualTo("Priya Raman");
		assertThat(body.get("user").get("role").asString()).isEqualTo("USER");

		// One account, one refresh token family.
		assertThat(userCount(sub)).isEqualTo(1);
	}

	@Test
	@DisplayName("the response carries exactly the fields the contract lists")
	void responseMatchesContract() throws Exception {
		JsonNode body = json(signIn(google.validToken(uniqueSub(), "Sam"), null)
				.andExpect(status().isOk()));

		assertThat(fieldNames(body)).containsExactlyInAnyOrder("accessToken",
				"accessTokenExpiresAt", "refreshToken", "refreshTokenExpiresAt", "user",
				"claimedReportCount", "newUser");
		// No email and no photo, because neither is stored (CLAUDE.md rule 13).
		assertThat(fieldNames(body.get("user")))
				.containsExactlyInAnyOrder("id", "displayName", "role", "createdAt");
	}

	@Test
	@DisplayName("signing in again reuses the account and starts a second family")
	void secondSignInReusesAccount() throws Exception {
		String sub = uniqueSub();
		signIn(google.validToken(sub, "Priya Raman"), null).andExpect(status().isOk());

		signIn(google.validToken(sub, "Priya R"), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.newUser").value(false))
				// The display name is refreshed from the token on every sign-in.
				.andExpect(jsonPath("$.user.displayName").value("Priya R"));

		assertThat(userCount(sub)).isEqualTo(1);
		// Each sign-in gets its own family, so tabs never share a refresh token
		// (docs/dataflow.md section 3.3).
		assertThat(familyCount(sub)).isEqualTo(2);
	}

	@Test
	@DisplayName("a bad ID token is 401 GOOGLE_TOKEN_INVALID and creates nothing")
	void badIdTokenIsRejected() throws Exception {
		String sub = uniqueSub();

		signIn(google.tokenWithAudience(sub, "999-other.apps.googleusercontent.com"), null)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("GOOGLE_TOKEN_INVALID"));

		assertThat(userCount(sub)).isZero();
	}

	@Test
	@DisplayName("a missing idToken is 400 VALIDATION_ERROR")
	void missingIdTokenIsValidationError() throws Exception {
		mockMvc.perform(post("/api/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	// --- claiming ------------------------------------------------------------

	@Test
	@DisplayName("a guest's reports move to the account, with a 30-day expiry")
	void signInClaimsGuestReports() throws Exception {
		String guestToken = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(guestToken);
		UUID firstReport = fixtures.pendingReport(sessionId);
		UUID secondReport = fixtures.pendingReport(sessionId);

		JsonNode body = json(signIn(google.validToken(uniqueSub(), "Priya"), guestToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.claimedReportCount").value(2)));

		String accessToken = body.get("accessToken").asString();

		// Reachable with the bearer token...
		for (UUID reportId : new UUID[] { firstReport, secondReport }) {
			mockMvc.perform(get("/api/reports/" + reportId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
					.andExpect(status().isOk());
		}

		// ...and the old guest token is gone with its session, so it is SESSION_INVALID
		// rather than a 404 (contract 2.3).
		mockMvc.perform(get("/api/reports/" + firstReport)
				.header(GuestAuthFilter.HEADER, guestToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("SESSION_INVALID"));

		assertThat(ownerOf(firstReport)).isEqualTo("user");
		assertThat(expiryDaysFromCreation(firstReport)).isEqualTo(30);
		assertThat(sessionExists(sessionId)).isFalse();
	}

	@Test
	@DisplayName("a report still processing is claimed too")
	void claimsInFlightReports() throws Exception {
		// The job writes by report id and its status updates are conditional on status, not
		// on owner, so a job in flight finishes against the new owner (LLD 7.1).
		String guestToken = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(guestToken);
		UUID reportId = fixtures.pendingReport(sessionId);

		jdbc.sql("UPDATE report SET status = 'PROCESSING' WHERE id = :id")
				.param("id", reportId)
				.update();

		signIn(google.validToken(uniqueSub(), "Priya"), guestToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.claimedReportCount").value(1));

		assertThat(ownerOf(reportId)).isEqualTo("user");
	}

	@Test
	@DisplayName("an unknown or expired guest token claims nothing and is not an error")
	void invalidGuestTokenClaimsNothing() throws Exception {
		signIn(google.validToken(uniqueSub(), "Priya"), "not-a-real-session-token")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.claimedReportCount").value(0));
	}

	@Test
	@DisplayName("claiming leaves another guest's reports alone")
	void claimOnlyTakesTheCallersReports() throws Exception {
		String mine = fixtures.sessionToken();
		UUID myReport = fixtures.pendingReport(fixtures.sessionIdFor(mine));

		String someoneElse = fixtures.sessionToken();
		UUID theirReport = fixtures.pendingReport(fixtures.sessionIdFor(someoneElse));

		signIn(google.validToken(uniqueSub(), "Priya"), mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.claimedReportCount").value(1));

		assertThat(ownerOf(myReport)).isEqualTo("user");
		assertThat(ownerOf(theirReport)).isEqualTo("session");
	}

	// --- refresh and logout --------------------------------------------------

	@Test
	@DisplayName("refresh returns new tokens and retires the old refresh token")
	void refreshRotates() throws Exception {
		JsonNode signedIn = json(signIn(google.validToken(uniqueSub(), "Priya"), null));
		String firstRefresh = signedIn.get("refreshToken").asString();

		JsonNode refreshed = json(refresh(firstRefresh).andExpect(status().isOk()));

		assertThat(refreshed.get("refreshToken").asString())
				.isNotEqualTo(firstRefresh)
				.hasSize(43);
		assertThat(fieldNames(refreshed)).containsExactlyInAnyOrder("accessToken",
				"accessTokenExpiresAt", "refreshToken", "refreshTokenExpiresAt", "user");

		// The retired token is now reuse, and the whole family goes with it.
		refresh(firstRefresh)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
		refresh(refreshed.get("refreshToken").asString())
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
	}

	@Test
	@DisplayName("an unknown refresh token is 401 REFRESH_TOKEN_INVALID")
	void unknownRefreshTokenIsRejected() throws Exception {
		refresh("not-a-real-token")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
	}

	@Test
	@DisplayName("a missing refreshToken is 400 VALIDATION_ERROR")
	void missingRefreshTokenIsValidationError() throws Exception {
		mockMvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	@DisplayName("logout revokes the family and always answers 204")
	void logoutRevokesFamily() throws Exception {
		JsonNode signedIn = json(signIn(google.validToken(uniqueSub(), "Priya"), null));
		String refreshToken = signedIn.get("refreshToken").asString();

		logout(refreshToken).andExpect(status().isNoContent());

		refresh(refreshToken)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

		// Unknown token: still 204, so a client can always complete a logout.
		logout("never-existed").andExpect(status().isNoContent());
	}

	// --- /api/me -------------------------------------------------------------

	@Test
	@DisplayName("GET /api/me returns the user and their live report count")
	void meReturnsUserAndReportCount() throws Exception {
		String guestToken = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(guestToken);
		fixtures.pendingReport(sessionId);
		fixtures.pendingReport(sessionId);

		String accessToken = json(signIn(google.validToken(uniqueSub(), "Priya Raman"),
				guestToken)).get("accessToken").asString();

		mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayName").value("Priya Raman"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.reportCount").value(2));
	}

	@Test
	@DisplayName("GET /api/me for a guest is 401 AUTH_REQUIRED")
	void meRejectsGuests() throws Exception {
		mockMvc.perform(get("/api/me").header(GuestAuthFilter.HEADER, fixtures.sessionToken()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	@DisplayName("GET /api/me with no credential is 401 AUTH_REQUIRED")
	void meRejectsAnonymous() throws Exception {
		mockMvc.perform(get("/api/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	@DisplayName("DELETE /api/me removes the account and everything it owns")
	void deleteAccountCascades() throws Exception {
		String guestToken = fixtures.sessionToken();
		UUID reportId = fixtures.pendingReport(fixtures.sessionIdFor(guestToken));

		String sub = uniqueSub();
		String accessToken = json(signIn(google.validToken(sub, "Priya"), guestToken))
				.get("accessToken").asString();

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isNoContent());

		assertThat(userCount(sub)).isZero();
		assertThat(reportExists(reportId)).isFalse();
		assertThat(familyCount(sub)).isZero();
	}

	@Test
	@DisplayName("the access token stops working the moment the account is gone")
	void deletedAccountTokenIsRejectedImmediately() throws Exception {
		String guestToken = fixtures.sessionToken();
		UUID reportId = fixtures.pendingReport(fixtures.sessionIdFor(guestToken));
		String accessToken = json(signIn(google.validToken(uniqueSub(), "Priya"), guestToken))
				.get("accessToken").asString();

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isNoContent());

		// The token still passes every signature and claim check for another 15 minutes. The
		// user-existence check is what makes it stop working now (docs/dataflow.md 8.1).
		mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

		mockMvc.perform(get("/api/reports/" + reportId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	// --- helpers -------------------------------------------------------------

	private ResultActions signIn(String idToken, String guestSessionToken) throws Exception {
		String body = guestSessionToken == null
				? "{\"idToken\":\"" + idToken + "\"}"
				: "{\"idToken\":\"" + idToken + "\",\"guestSessionToken\":\""
						+ guestSessionToken + "\"}";

		return mockMvc.perform(post("/api/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	private ResultActions logout(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/auth/logout")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	private static JsonNode json(ResultActions actions) throws Exception {
		return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private static Iterable<String> fieldNames(JsonNode node) {
		return node.propertyNames();
	}

	/** Unique per call, because {@code google_sub} is unique and tests share a database. */
	private static String uniqueSub() {
		return "test-sub-" + UUID.randomUUID();
	}

	private int userCount(String googleSub) {
		Integer count = jdbc.sql("SELECT count(*) FROM app_user WHERE google_sub = :sub")
				.param("sub", googleSub)
				.query(Integer.class)
				.single();
		return count != null ? count : 0;
	}

	private int familyCount(String googleSub) {
		Integer count = jdbc.sql("""
				SELECT count(DISTINCT family_id) FROM refresh_token t
				 JOIN app_user u ON u.id = t.user_id
				 WHERE u.google_sub = :sub
				""")
				.param("sub", googleSub)
				.query(Integer.class)
				.single();
		return count != null ? count : 0;
	}

	private String ownerOf(UUID reportId) {
		return jdbc.sql("""
				SELECT CASE WHEN user_id IS NOT NULL THEN 'user' ELSE 'session' END
				  FROM report WHERE id = :id
				""")
				.param("id", reportId)
				.query(String.class)
				.single();
	}

	private boolean reportExists(UUID reportId) {
		Integer count = jdbc.sql("SELECT count(*) FROM report WHERE id = :id")
				.param("id", reportId)
				.query(Integer.class)
				.single();
		return count != null && count > 0;
	}

	private boolean sessionExists(UUID sessionId) {
		Integer count = jdbc.sql("SELECT count(*) FROM session WHERE id = :id")
				.param("id", sessionId)
				.query(Integer.class)
				.single();
		return count != null && count > 0;
	}

	/** Expiry is recomputed from {@code created_at}, so this must be exactly the retention. */
	private int expiryDaysFromCreation(UUID reportId) {
		Integer days = jdbc.sql("""
				SELECT EXTRACT(DAY FROM (expires_at - created_at))::int
				  FROM report WHERE id = :id
				""")
				.param("id", reportId)
				.query(Integer.class)
				.single();
		return days != null ? days : -1;
	}
}
