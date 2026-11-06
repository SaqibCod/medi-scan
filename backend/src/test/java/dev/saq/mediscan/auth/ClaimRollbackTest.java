package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestGoogleIssuer;

/**
 * Sign-in is all-or-nothing: if anything fails after the reports have been claimed, the guest
 * keeps their session and their reports.
 *
 * <p>This is the test the transaction boundary exists for, and it cannot be written any other
 * way than by breaking something on purpose. The claim moves reports and then deletes the
 * guest session; a failure in between - or afterwards, in the refresh-token insert - would
 * otherwise leave the user with reports they cannot reach and the guest with nothing
 * (LLD 7.1, 13.3 case 8).
 *
 * <p>The failure is injected into the step that runs last, so the claim and the session delete
 * have both already happened by the time it throws. Anything less would not prove the rollback
 * covers them.
 */
@Import(TestGoogleVerifierConfig.class)
class ClaimRollbackTest extends PostgresTestBase {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TestGoogleIssuer google;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private JdbcClient jdbc;

	@MockitoSpyBean
	private RefreshTokenService refreshTokens;

	@Test
	@DisplayName("a failure after the claim leaves the guest owning their reports")
	void failureAfterClaimRollsEverythingBack() throws Exception {
		String guestToken = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(guestToken);
		UUID reportId = fixtures.pendingReport(sessionId);
		String sub = "rollback-" + UUID.randomUUID();

		// Issuing the refresh token is the last write in the transaction, so the claim and
		// the session delete have already run when this throws.
		willThrow(new IllegalStateException("injected failure after the claim"))
				.given(refreshTokens).issueNewFamily(any());

		mockMvc.perform(post("/api/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"idToken\":\"" + google.validToken(sub, "Priya") + "\","
						+ "\"guestSessionToken\":\"" + guestToken + "\"}"))
				.andExpect(status().isInternalServerError());

		// The report is still the guest's...
		assertThat(sessionIdOf(reportId)).isEqualTo(sessionId);
		assertThat(userIdOf(reportId)).isNull();

		// ...the session still exists, so the guest's token still works...
		assertThat(sessionExists(sessionId)).isTrue();
		mockMvc.perform(get("/api/reports/" + reportId)
				.header(GuestAuthFilter.HEADER, guestToken))
				.andExpect(status().isOk());

		// ...and no half-made account was left behind.
		assertThat(userCount(sub)).isZero();
	}

	private UUID sessionIdOf(UUID reportId) {
		return jdbc.sql("SELECT session_id FROM report WHERE id = :id")
				.param("id", reportId).query(UUID.class).optional().orElse(null);
	}

	private UUID userIdOf(UUID reportId) {
		return jdbc.sql("SELECT user_id FROM report WHERE id = :id")
				.param("id", reportId).query(UUID.class).optional().orElse(null);
	}

	private boolean sessionExists(UUID sessionId) {
		Integer count = jdbc.sql("SELECT count(*) FROM session WHERE id = :id")
				.param("id", sessionId).query(Integer.class).single();
		return count != null && count > 0;
	}

	private int userCount(String googleSub) {
		Integer count = jdbc.sql("SELECT count(*) FROM app_user WHERE google_sub = :sub")
				.param("sub", googleSub).query(Integer.class).single();
		return count != null ? count : 0;
	}
}
