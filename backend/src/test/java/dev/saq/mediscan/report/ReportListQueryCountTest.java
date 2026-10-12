package dev.saq.mediscan.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.QueryCountingDataSourceHolder;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * {@code GET /api/reports} runs a fixed, small number of queries regardless of how many
 * {@code DONE} reports are on the page (contract section 4.5, LLD 5, LLD 9 test 10).
 *
 * <p>Shares the suite's default context, same as {@link ReportListControllerTest} - the
 * counting mechanism ({@code QueryCountingDataSourceHolder} and its installer, in
 * {@code support}) is wired into every test context already, so this class needs no extra
 * configuration of its own.
 */
class ReportListQueryCountTest extends PostgresTestBase {

	private static final String PATH = "/api/reports";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private QueryCountingDataSourceHolder countingDataSource;

	@Test
	void listingTwentyDoneReportsRunsAFixedNumberOfQueries() throws Exception {
		String token = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(token);
		for (int i = 0; i < 20; i++) {
			UUID reportId = fixtures.doneReport(sessionId, LocalDate.of(2026, 1, 1 + i));
			fixtures.biomarker(reportId, 0, "LDL", "ldl", "ldl-cholesterol", 140.0, "mg/dL",
					0.0, 100.0, Flag.HIGH);
		}

		countingDataSource.reset();

		mockMvc.perform(get(PATH).header(GuestAuthFilter.HEADER, token).param("size", "20"))
				.andExpect(status().isOk());

		// GuestAuthFilter's own session lookup, plus the three list queries (count, page, and
		// one grouped counts query) - never one counts query per report.
		assertThat(countingDataSource.count()).isLessThanOrEqualTo(4);
	}
}
