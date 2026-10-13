package dev.saq.mediscan.stats;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.StatsFixtures;
import dev.saq.mediscan.support.TestTokens;
import dev.saq.mediscan.user.UserRole;

/**
 * {@code GET /api/admin/stats} (contract section 6, LLD 7, LLD 9 items 25-30).
 *
 * <p>Clears every table the response reads before each test: {@code users.total} and
 * {@code activeReports} are unavoidably global counts, so an exact-value assertion needs a
 * clean slate, the same pattern several other test classes already use.
 */
class AdminStatsControllerTest extends PostgresTestBase {

	private static final String PATH = "/api/admin/stats";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private StatsFixtures stats;

	@BeforeEach
	void cleanSlate() {
		stats.clear();
		fixtures.clear();
	}

	@Test
	@DisplayName("a guest is AUTH_REQUIRED, a USER is FORBIDDEN, an ADMIN gets 200")
	void authorizationMatrix() throws Exception {
		mockMvc.perform(get(PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

		String guestToken = fixtures.sessionToken();
		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, guestToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

		UUID regularUser = fixtures.user();
		String bearer = "Bearer " + TestTokens.signingWithCurrentKey().validFor(regularUser, UserRole.USER);
		mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		UUID adminId = fixtures.adminUser();
		String adminBearer = "Bearer " + TestTokens.signingWithCurrentKey().validFor(adminId, UserRole.ADMIN);
		mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, adminBearer))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("exactly `days` entries, oldest first; gap days are zero-filled")
	void gapDaysAreZeroFilled() throws Exception {
		UUID adminId = fixtures.adminUser();
		String auth = adminBearer(adminId);

		LocalDate today = LocalDate.now();
		stats.llmUsage(today, 5);
		stats.dailyStats(today, 100, 50, 2, 1, 0, 0);
		// today - 1 has no rows at all.
		stats.llmUsage(today.minusDays(2), 3);

		mockMvc.perform(get(PATH).param("days", "3").header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.days.length()").value(3))
				.andExpect(jsonPath("$.days[0].date").value(today.minusDays(2).toString()))
				.andExpect(jsonPath("$.days[0].llmCalls").value(3))
				.andExpect(jsonPath("$.days[1].date").value(today.minusDays(1).toString()))
				.andExpect(jsonPath("$.days[1].llmCalls").value(0))
				.andExpect(jsonPath("$.days[1].reportsCreated").value(0))
				.andExpect(jsonPath("$.days[2].date").value(today.toString()))
				.andExpect(jsonPath("$.days[2].llmCalls").value(5))
				.andExpect(jsonPath("$.days[2].reportsCreated").value(2));
	}

	@Test
	@DisplayName("failuresByCode and avgProcessingMsBySource sum and average across days; a source with no rows is absent, no IMAGE key")
	void failuresAndProcessingAverages() throws Exception {
		UUID adminId = fixtures.adminUser();
		String auth = adminBearer(adminId);

		LocalDate today = LocalDate.now();
		stats.failure(today, "UNREADABLE", 2);
		stats.failure(today.minusDays(1), "UNREADABLE", 1);
		stats.failure(today, "NO_RESULTS_FOUND", 1);

		stats.processing(today, "PDF", 6000, 2);
		stats.processing(today.minusDays(1), "PDF", 3000, 1);
		// 9000ms / 3 calls = 3000ms average.

		mockMvc.perform(get(PATH).param("days", "7").header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.failuresByCode.UNREADABLE").value(3))
				.andExpect(jsonPath("$.failuresByCode.NO_RESULTS_FOUND").value(1))
				.andExpect(jsonPath("$.avgProcessingMsBySource.PDF").value(3000))
				.andExpect(jsonPath("$.avgProcessingMsBySource.IMAGE").doesNotExist())
				.andExpect(jsonPath("$.avgProcessingMsBySource.TEXT").doesNotExist());
	}

	@Test
	@DisplayName("activeReports and users counts are right; an expired report is not counted")
	void activeReportsAndUserCounts() throws Exception {
		UUID adminId = fixtures.adminUser();
		String auth = adminBearer(adminId);

		UUID regularUser = fixtures.user();
		fixtures.pendingUserReport(regularUser);

		String guestToken = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(guestToken);
		fixtures.pendingReport(sessionId);
		fixtures.expiredReport(sessionId);

		mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isOk())
				// adminUser + regularUser = 2 app_user rows.
				.andExpect(jsonPath("$.users.total").value(2))
				.andExpect(jsonPath("$.activeReports.guest").value(1))
				.andExpect(jsonPath("$.activeReports.user").value(1));
	}

	@Test
	@DisplayName("days=0, days=91, and days=abc are 400 VALIDATION_ERROR")
	void invalidDaysParameter() throws Exception {
		UUID adminId = fixtures.adminUser();
		String auth = adminBearer(adminId);

		mockMvc.perform(get(PATH).param("days", "0").header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		mockMvc.perform(get(PATH).param("days", "91").header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		mockMvc.perform(get(PATH).param("days", "abc").header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	@DisplayName("the response carries no field named like an id, a name, or a subject")
	void responseHasNoIdentifyingFields() throws Exception {
		UUID adminId = fixtures.adminUser();
		String auth = adminBearer(adminId);

		for (java.lang.reflect.RecordComponent component : AdminStats.class.getRecordComponents()) {
			String name = component.getName().toLowerCase();
			org.assertj.core.api.Assertions.assertThat(name)
					.doesNotContain("id").doesNotContain("name").doesNotContain("sub");
		}

		mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isOk());
	}

	private static String adminBearer(UUID adminId) {
		return "Bearer " + TestTokens.signingWithCurrentKey().validFor(adminId, UserRole.ADMIN);
	}
}
