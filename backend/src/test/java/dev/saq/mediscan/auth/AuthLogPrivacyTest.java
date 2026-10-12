package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.support.LogCapture;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestGoogleIssuer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The canary test for authentication: drive every auth path, then read the logs back and
 * assert that nothing secret is in them.
 *
 * <p>Modelled on the report pipeline's canary. {@code CLAUDE.md} rule 14 and LLD 12 say no raw
 * token, no Google {@code sub} and no display name ever reaches a log line, and the only way
 * to hold that is to check - including at levels a normal run discards, because a {@code DEBUG}
 * line on a developer's machine is as much a leak as an {@code INFO} one in production.
 *
 * <p>Captures Spring Security's own loggers as well as the application's. The framework logs
 * at {@code DEBUG} and {@code TRACE} around authentication failures, and a leak there would be
 * just as real as one of ours.
 */
@Import(TestGoogleVerifierConfig.class)
class AuthLogPrivacyTest extends PostgresTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Distinctive enough that a substring match cannot be a coincidence. */
	private static final String CANARY_SUB = "ZZCANARY-sub-8814520";
	private static final String CANARY_NAME = "ZZCanary Testpatient";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TestGoogleIssuer google;

	@Autowired
	private ReportFixtures fixtures;

	@Test
	@DisplayName("no raw token, Google sub or display name appears in any log line")
	void authPathsLeakNothing() throws Exception {
		String guestToken = fixtures.sessionToken();
		UUID reportId = fixtures.pendingReport(fixtures.sessionIdFor(guestToken));
		String idToken = google.validToken(CANARY_SUB, CANARY_NAME);

		String logs;
		String accessToken;
		String refreshToken;
		String rotatedRefresh;

		try (LogCapture capture = LogCapture.startForAll()) {
			// Sign in, claiming a report.
			JsonNode signedIn = JSON.readTree(mockMvc.perform(post("/api/auth/google")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"idToken\":\"" + idToken + "\",\"guestSessionToken\":\""
							+ guestToken + "\"}"))
					.andReturn().getResponse().getContentAsString());

			accessToken = signedIn.get("accessToken").asString();
			refreshToken = signedIn.get("refreshToken").asString();

			// A successful bearer request.
			mockMvc.perform(get("/api/reports/" + reportId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));

			// A rejected bearer request, which is where the framework logs most.
			mockMvc.perform(get("/api/reports/" + reportId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken + "tampered"));

			// Refresh, then reuse the retired token so the reuse branch runs.
			rotatedRefresh = JSON.readTree(refresh(refreshToken)).get("refreshToken").asString();
			refresh(refreshToken);

			// A rejected Google token.
			mockMvc.perform(post("/api/auth/google")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"idToken\":\"" + google.expiredToken(CANARY_SUB) + "\"}"));

			// /api/me, then delete the account.
			mockMvc.perform(get("/api/me")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
			mockMvc.perform(delete("/api/me")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));

			logs = capture.allText();
		}

		// Something was logged, or this test would pass by capturing nothing at all.
		assertThat(logs).isNotEmpty();

		assertThat(logs)
				.as("the Google subject identifies a person")
				.doesNotContain(CANARY_SUB);
		assertThat(logs)
				.as("the display name is a real person's name")
				.doesNotContain(CANARY_NAME)
				.doesNotContain("ZZCanary");
		assertThat(logs)
				.as("the Google ID token")
				.doesNotContain(idToken);
		assertThat(logs)
				.as("the app's own access token")
				.doesNotContain(accessToken);
		assertThat(logs)
				.as("the refresh token, before and after rotation")
				.doesNotContain(refreshToken)
				.doesNotContain(rotatedRefresh);
		assertThat(logs)
				.as("the guest session token")
				.doesNotContain(guestToken);
	}

	@Test
	@DisplayName("the reuse warning carries ids only")
	void reuseWarningCarriesIdsOnly() throws Exception {
		String idToken = google.validToken(CANARY_SUB + "-reuse", CANARY_NAME);

		try (LogCapture capture = LogCapture.startForAll()) {
			JsonNode signedIn = JSON.readTree(mockMvc.perform(post("/api/auth/google")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"idToken\":\"" + idToken + "\"}"))
					.andReturn().getResponse().getContentAsString());

			String refreshToken = signedIn.get("refreshToken").asString();
			refresh(refreshToken);
			refresh(refreshToken);

			String logs = capture.allText();

			// The event has to be visible - a silent reuse detection would be useless for
			// anyone reading the logs after an incident.
			assertThat(logs).contains("reuse-detected");
			assertThat(logs).doesNotContain(refreshToken);
		}
	}

	private String refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andReturn().getResponse().getContentAsString();
	}
}
