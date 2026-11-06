package dev.saq.mediscan.report;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestTokens;
import dev.saq.mediscan.user.UserRole;

/**
 * {@code GET /api/reports} (contract section 4.5, LLD 5, LLD 9 items 1-9 and 11).
 *
 * <p>The query-count test (item 10) lives in {@link ReportListQueryCountTest} because it needs
 * its own counting {@code DataSource} bean, which would otherwise apply to every test in this
 * class.
 */
class ReportListControllerTest extends PostgresTestBase {

	private static final String PATH = "/api/reports";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private ReportOwnershipService ownership;

	@Test
	@DisplayName("a guest sees only their own session's reports")
	void guestSeesOnlyOwnReports() throws Exception {
		String token = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(token);
		fixtures.doneReport(sessionId, LocalDate.of(2026, 1, 1));

		String otherToken = fixtures.sessionToken();
		UUID otherSession = fixtures.sessionIdFor(otherToken);
		fixtures.doneReport(otherSession, LocalDate.of(2026, 1, 2));

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	@DisplayName("a user sees only their own reports, not another user's")
	void userSeesOnlyOwnReports() throws Exception {
		UUID userId = fixtures.user();
		fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1));

		UUID otherUserId = fixtures.user();
		fixtures.doneUserReport(otherUserId, LocalDate.of(2026, 1, 2));

		String token = TestTokens.signingWithCurrentKey().validFor(userId, UserRole.USER);

		mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	@DisplayName("newest first")
	void newestFirst() throws Exception {
		String token = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(token);
		fixtures.doneReport(sessionId, LocalDate.of(2026, 1, 1));
		UUID second = fixtures.doneReport(sessionId, LocalDate.of(2026, 1, 2));

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(second.toString()));
	}

	@Test
	@DisplayName("paging: page, size, totalItems, totalPages; a page past the end is empty")
	void paging() throws Exception {
		String token = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(token);
		for (int i = 0; i < 3; i++) {
			fixtures.doneReport(sessionId, LocalDate.of(2026, 1, 1 + i));
		}

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.totalItems").value(3))
				.andExpect(jsonPath("$.totalPages").value(2));

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token)
				.param("size", "2").param("page", "5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0))
				.andExpect(jsonPath("$.totalItems").value(3));
	}

	@Test
	@DisplayName("status filter: each of the four values")
	void statusFilter() throws Exception {
		String token = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(token);
		fixtures.pendingReport(sessionId);
		fixtures.doneReport(sessionId, LocalDate.of(2026, 1, 1));
		fixtures.failedReport(sessionId, ReportErrorCode.UNREADABLE);

		for (String status : new String[] { "PENDING", "DONE", "FAILED" }) {
			mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("status", status))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.items.length()").value(1))
					.andExpect(jsonPath("$.items[0].status").value(status));
		}

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("status", "PROCESSING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	@DisplayName("an expired report is never listed, even before retention runs")
	void expiredReportsAreHidden() throws Exception {
		String token = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(token);
		fixtures.expiredReport(sessionId);

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	@DisplayName("counts and collectedOn are null unless DONE; errorCode only for FAILED")
	void countsAndCollectedOnOnlyWhenDone() throws Exception {
		String token = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(token);

		UUID pending = fixtures.pendingReport(sessionId);
		UUID done = fixtures.doneReport(sessionId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(done, 0, "LDL", "ldl", "ldl-cholesterol", 140.0, "mg/dL", 0.0, 100.0, Flag.HIGH);
		UUID failed = fixtures.failedReport(sessionId, ReportErrorCode.UNREADABLE);

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("status", "PENDING"))
				.andExpect(jsonPath("$.items[0].id").value(pending.toString()))
				.andExpect(jsonPath("$.items[0].counts").doesNotExist())
				.andExpect(jsonPath("$.items[0].collectedOn").doesNotExist())
				.andExpect(jsonPath("$.items[0].errorCode").doesNotExist());

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("status", "DONE"))
				.andExpect(jsonPath("$.items[0].id").value(done.toString()))
				.andExpect(jsonPath("$.items[0].counts.total").value(1))
				.andExpect(jsonPath("$.items[0].counts.high").value(1))
				.andExpect(jsonPath("$.items[0].collectedOn").value("2026-01-01"))
				.andExpect(jsonPath("$.items[0].errorCode").doesNotExist());

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("status", "FAILED"))
				.andExpect(jsonPath("$.items[0].id").value(failed.toString()))
				.andExpect(jsonPath("$.items[0].errorCode").value("UNREADABLE"))
				.andExpect(jsonPath("$.items[0].counts").doesNotExist());
	}

	@Test
	@DisplayName("invalid page, size and status each return 400 VALIDATION_ERROR")
	void invalidParameters() throws Exception {
		String token = fixtures.sessionToken();

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("page", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("page"));

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("size", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("size"));

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("size", "51"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("size"));

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("status", "NOT_A_STATUS"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("status"));
	}

	@Test
	@DisplayName("after sign-in claims a guest's reports, the user's list contains them")
	void claimedReportsAppearInUsersList() throws Exception {
		String guestToken = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(guestToken);
		fixtures.doneReport(sessionId, LocalDate.of(2026, 1, 1));

		UUID userId = fixtures.user();
		ownership.claimGuestReports(sessionId, userId, 30);

		String token = TestTokens.signingWithCurrentKey().validFor(userId, UserRole.USER);
		mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	@DisplayName("no credential is SESSION_INVALID; an expired bearer token is TOKEN_EXPIRED")
	void missingOrBadCredential() throws Exception {
		mockMvc.perform(get(PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("SESSION_INVALID"));

		UUID userId = fixtures.user();
		String expired = TestTokens.signingWithCurrentKey().expiredFor(userId, UserRole.USER);
		mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}
}
