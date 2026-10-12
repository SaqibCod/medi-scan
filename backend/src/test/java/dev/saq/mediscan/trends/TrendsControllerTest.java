package dev.saq.mediscan.trends;

import static org.assertj.core.api.Assertions.assertThat;
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

import dev.saq.mediscan.analysis.TestNameNormalizer;
import dev.saq.mediscan.report.Flag;
import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.support.LogCapture;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestTokens;
import dev.saq.mediscan.user.UserRole;

/**
 * {@code GET /api/trends} and {@code GET /api/trends/markers} (contract section 5, LLD 6,
 * LLD 9 items 12-24).
 */
class TrendsControllerTest extends PostgresTestBase {

	private static final String TRENDS = "/api/trends";
	private static final String MARKERS = "/api/trends/markers";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReportFixtures fixtures;

	private String bearerFor(UUID userId) {
		return "Bearer " + TestTokens.signingWithCurrentKey().validFor(userId, UserRole.USER);
	}

	// --- auth -----------------------------------------------------------------

	@Test
	@DisplayName("a guest gets AUTH_REQUIRED on both trend endpoints")
	void guestIsAuthRequired() throws Exception {
		String guestToken = fixtures.sessionToken();

		mockMvc.perform(get(TRENDS).param("marker", "ldl-cholesterol")
				.header(GuestAuthFilter.HEADER, guestToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

		mockMvc.perform(get(MARKERS).header(GuestAuthFilter.HEADER, guestToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	// --- matching ---------------------------------------------------------------

	@Test
	@DisplayName("matches by slug")
	void matchesBySlug() throws Exception {
		UUID userId = fixtures.user();
		UUID reportId = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(reportId, 0, "LDL Cholesterol", norm("LDL Cholesterol"), "ldl-cholesterol",
				140.0, "mg/dL", 0.0, 100.0, Flag.HIGH);

		mockMvc.perform(get(TRENDS).param("marker", "ldl-cholesterol")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.marker").value("ldl-cholesterol"))
				.andExpect(jsonPath("$.series[0].points.length()").value(1));
	}

	@Test
	@DisplayName("matches by normalized name, ignoring case, punctuation and extra spaces")
	void matchesByNormalizedNameWhenNoSlug() throws Exception {
		UUID userId = fixtures.user();
		UUID reportId = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(reportId, 0, "Vitamin D, 25-OH", norm("Vitamin D, 25-OH"), null,
				32.0, "ng/mL", 30.0, 100.0, Flag.NORMAL);

		mockMvc.perform(get(TRENDS).param("marker", "  vitamin   D  25 OH  ")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series[0].points.length()").value(1));
	}

	@Test
	@DisplayName("qualitative values (null numeric_value) are excluded")
	void qualitativeValuesExcluded() throws Exception {
		UUID userId = fixtures.user();
		UUID reportId = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(reportId, 0, "Culture", norm("Culture"), null, null, null, null, null,
				Flag.UNKNOWN);

		mockMvc.perform(get(TRENDS).param("marker", "culture")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series.length()").value(0));
	}

	// --- units ------------------------------------------------------------------

	@Test
	@DisplayName("two units produce two series, never converted")
	void twoUnitsProduceTwoSeries() throws Exception {
		UUID userId = fixtures.user();
		UUID report1 = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(report1, 0, "Glucose", norm("Glucose"), "glucose", 90.0, "mg/dL",
				70.0, 100.0, Flag.NORMAL);
		UUID report2 = fixtures.doneUserReport(userId, LocalDate.of(2026, 2, 1));
		fixtures.biomarker(report2, 0, "Glucose", norm("Glucose"), "glucose", 5.0, "mmol/L",
				3.9, 5.6, Flag.NORMAL);

		mockMvc.perform(get(TRENDS).param("marker", "glucose")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series.length()").value(2));
	}

	@Test
	@DisplayName("a null unit forms its own series with unit: null")
	void nullUnitFormsItsOwnSeries() throws Exception {
		UUID userId = fixtures.user();
		UUID reportId = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(reportId, 0, "Custom Score", norm("Custom Score"), null, 7.0, null,
				null, null, Flag.UNKNOWN);

		mockMvc.perform(get(TRENDS).param("marker", "custom score")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series.length()").value(1))
				.andExpect(jsonPath("$.series[0].unit").doesNotExist());
	}

	// --- dates --------------------------------------------------------------

	@Test
	@DisplayName("missing collectedOn dates the point from the upload instant, with dateSource UPLOADED")
	void missingCollectedOnUsesUploadDate() throws Exception {
		UUID userId = fixtures.user();
		UUID reportId = fixtures.doneUserReport(userId, null);
		fixtures.biomarker(reportId, 0, "TSH", norm("TSH"), "tsh", 2.0, "mIU/L", 0.4, 4.0, Flag.NORMAL);

		mockMvc.perform(get(TRENDS).param("marker", "tsh")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series[0].points[0].dateSource").value("UPLOADED"));
	}

	// --- ordering and the point cap -----------------------------------------

	@Test
	@DisplayName("points are oldest first; a 130-point series keeps the latest 100, still oldest first")
	void capsAtOneHundredNewestKeptOldestFirst() throws Exception {
		UUID userId = fixtures.user();
		for (int i = 0; i < 130; i++) {
			UUID reportId = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1).plusDays(i));
			fixtures.biomarker(reportId, 0, "TSH", norm("TSH"), "tsh", 1.0 + i, "mIU/L", 0.4, 4.0,
					Flag.NORMAL);
		}

		mockMvc.perform(get(TRENDS).param("marker", "tsh")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series[0].points.length()").value(100))
				// day index 30 is the 31st day (0-based), the oldest of the kept 100.
				.andExpect(jsonPath("$.series[0].points[0].date")
						.value(LocalDate.of(2026, 1, 1).plusDays(30).toString()))
				.andExpect(jsonPath("$.series[0].points[99].date")
						.value(LocalDate.of(2026, 1, 1).plusDays(129).toString()));
	}

	// --- scope ----------------------------------------------------------------

	@Test
	@DisplayName("only DONE, not-expired reports count")
	void onlyDoneAndNotExpiredCount() throws Exception {
		UUID userId = fixtures.user();
		UUID pending = fixtures.pendingUserReport(userId);
		fixtures.biomarker(pending, 0, "TSH", norm("TSH"), "tsh", 2.0, "mIU/L", 0.4, 4.0, Flag.NORMAL);

		UUID done = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(done, 0, "TSH", norm("TSH"), "tsh", 2.0, "mIU/L", 0.4, 4.0, Flag.NORMAL);

		mockMvc.perform(get(TRENDS).param("marker", "tsh")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series[0].points.length()").value(1));
	}

	@Test
	@DisplayName("another user's data with the same marker never appears")
	void anotherUsersDataNeverAppears() throws Exception {
		UUID userId = fixtures.user();
		UUID otherUserId = fixtures.user();
		UUID otherReport = fixtures.doneUserReport(otherUserId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(otherReport, 0, "TSH", norm("TSH"), "tsh", 2.0, "mIU/L", 0.4, 4.0, Flag.NORMAL);

		mockMvc.perform(get(TRENDS).param("marker", "tsh")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series.length()").value(0));
	}

	@Test
	@DisplayName("an unknown marker returns 200 with an empty series")
	void unknownMarkerReturnsEmptySeries() throws Exception {
		UUID userId = fixtures.user();

		mockMvc.perform(get(TRENDS).param("marker", "not-a-real-marker")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series.length()").value(0));
	}

	@Test
	@DisplayName("missing, blank, or 101-character marker is 400 VALIDATION_ERROR")
	void invalidMarkerParameter() throws Exception {
		UUID userId = fixtures.user();
		String auth = bearerFor(userId);

		mockMvc.perform(get(TRENDS).header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("marker"));

		mockMvc.perform(get(TRENDS).param("marker", "   ").header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		mockMvc.perform(get(TRENDS).param("marker", "a".repeat(101))
				.header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	// --- markers list ---------------------------------------------------------

	@Test
	@DisplayName("markers list: needs two reports, sorted by reportCount then displayName, excludes singletons")
	void markersListNeedsTwoReports() throws Exception {
		UUID userId = fixtures.user();

		// ldl-cholesterol: 2 reports.
		UUID r1 = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(r1, 0, "LDL Cholesterol", norm("LDL Cholesterol"), "ldl-cholesterol",
				140.0, "mg/dL", 0.0, 100.0, Flag.HIGH);
		UUID r2 = fixtures.doneUserReport(userId, LocalDate.of(2026, 2, 1));
		fixtures.biomarker(r2, 0, "LDL Cholesterol", norm("LDL Cholesterol"), "ldl-cholesterol",
				150.0, "mg/dL", 0.0, 100.0, Flag.HIGH);

		// tsh: only 1 report - must be excluded.
		UUID r3 = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 15));
		fixtures.biomarker(r3, 0, "TSH", norm("TSH"), "tsh", 2.0, "mIU/L", 0.4, 4.0, Flag.NORMAL);

		mockMvc.perform(get(MARKERS).header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].marker").value("ldl-cholesterol"))
				.andExpect(jsonPath("$[0].reportCount").value(2));
	}

	// --- repeated tests -------------------------------------------------------

	@Test
	@DisplayName("a repeated test in one report keeps all its rows")
	void repeatedTestInOneReportKeepsAllRows() throws Exception {
		UUID userId = fixtures.user();
		UUID reportId = fixtures.doneUserReport(userId, LocalDate.of(2026, 1, 1));
		fixtures.biomarker(reportId, 0, "Glucose", norm("Glucose"), "glucose", 90.0, "mg/dL",
				70.0, 100.0, Flag.NORMAL);
		fixtures.biomarker(reportId, 1, "Glucose", norm("Glucose"), "glucose", 180.0, "mg/dL",
				70.0, 100.0, Flag.HIGH);

		mockMvc.perform(get(TRENDS).param("marker", "glucose")
				.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series[0].points.length()").value(2));
	}

	// --- privacy in logs -------------------------------------------------------

	@Test
	@DisplayName("no marker name, value, or date reaches a log line")
	void nothingSensitiveIsLogged() throws Exception {
		UUID userId = fixtures.user();
		UUID reportId = fixtures.doneUserReport(userId, LocalDate.of(2026, 3, 17));
		String canaryMarker = "ZZCANARY-Marker-4471";
		fixtures.biomarker(reportId, 0, canaryMarker, norm(canaryMarker), null, 123.456, "mg/dL",
				0.0, 10.0, Flag.HIGH);

		try (LogCapture logs = LogCapture.start()) {
			mockMvc.perform(get(TRENDS).param("marker", canaryMarker)
					.header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
					.andExpect(status().isOk());
			mockMvc.perform(get(MARKERS).header(HttpHeaders.AUTHORIZATION, bearerFor(userId)))
					.andExpect(status().isOk());

			String captured = logs.allText();
			assertThat(captured).doesNotContain(canaryMarker);
			assertThat(captured).doesNotContain("123.456");
			assertThat(captured).doesNotContain("2026-03-17");
		}
	}

	private static String norm(String testName) {
		return TestNameNormalizer.normalize(testName);
	}
}
