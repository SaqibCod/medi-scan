package dev.saq.mediscan.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * {@code GET} and {@code DELETE /api/reports/{id}} (contract sections 4.2 and 4.3).
 *
 * <p>The authorization cases are the ones that matter most. {@code CLAUDE.md} rule 7 requires
 * that someone else's report is indistinguishable from one that never existed - both
 * {@code 404}, never {@code 403} - so the API cannot be used to discover which ids are real.
 */
class ReportResultsTest extends PostgresTestBase {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	ReportStatusService statuses;

	@Autowired
	ReportResultSaver saver;

	private String token;
	private UUID sessionId;

	@BeforeEach
	void freshSession() {
		fixtures.clear();
		token = fixtures.sessionToken();
		sessionId = fixtures.sessionIdFor(token);
	}

	// --- status while processing ---------------------------------------------

	@Test
	@DisplayName("a pending report returns null result and null error")
	void returnsPendingReport() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(reportId.toString()))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.sourceType").value("SAMPLE"))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				// Present and null, not absent: the client reads these without checking for
				// the key (contract section 4.2).
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.result").value(nullValue()));
	}

	@Test
	@DisplayName("a processing report reports PROCESSING")
	void returnsProcessingReport() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);
		statuses.markProcessing(reportId);

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROCESSING"));
	}

	// --- failed --------------------------------------------------------------

	@Test
	@DisplayName("a failed report is a 200 with an error object")
	void returnsFailedReport() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);
		statuses.markProcessing(reportId);
		statuses.markFailed(reportId, ReportErrorCode.UNREADABLE);

		// A failed report is still a 200: the request succeeded, the processing did not.
		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.error.code").value("UNREADABLE"))
				.andExpect(jsonPath("$.error.message")
						.value("We couldn't read text from this file. Try a clearer image."))
				.andExpect(jsonPath("$.result").value(nullValue()));
	}

	@Test
	@DisplayName("DOCUMENT_TOO_LONG carries the message the contract added in 2.2")
	void returnsDocumentTooLong() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);
		statuses.markProcessing(reportId);
		statuses.markFailed(reportId, ReportErrorCode.DOCUMENT_TOO_LONG);

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(jsonPath("$.error.code").value("DOCUMENT_TOO_LONG"))
				.andExpect(jsonPath("$.error.message")
						.value("This document is too long to process. Try a shorter report."));
	}

	// --- done ----------------------------------------------------------------

	@Test
	@DisplayName("a finished report returns its values, summary and counts")
	void returnsDoneReport() throws Exception {
		UUID reportId = saveFinishedReport();

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DONE"))
				.andExpect(jsonPath("$.error").value(nullValue()))

				.andExpect(jsonPath("$.result.collectedOn").value("2026-09-28"))
				.andExpect(jsonPath("$.result.biomarkers.length()").value(2))
				.andExpect(jsonPath("$.result.summary").value("Your LDL cholesterol is high."))
				.andExpect(jsonPath("$.result.highlights.length()").value(1))

				// Computed server-side, so the tally under a results page cannot disagree
				// with the rows above it.
				.andExpect(jsonPath("$.result.counts.total").value(2))
				.andExpect(jsonPath("$.result.counts.low").value(0))
				.andExpect(jsonPath("$.result.counts.normal").value(0))
				.andExpect(jsonPath("$.result.counts.high").value(1))
				.andExpect(jsonPath("$.result.counts.unknown").value(1));
	}

	@Test
	@DisplayName("every biomarker field in the contract example is present and correctly typed")
	void biomarkerShapeMatchesContract() throws Exception {
		UUID reportId = saveFinishedReport();

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(jsonPath("$.result.biomarkers[0].id").isNotEmpty())
				.andExpect(jsonPath("$.result.biomarkers[0].testName").value("LDL Cholesterol"))
				.andExpect(jsonPath("$.result.biomarkers[0].rawValue").value("162"))
				.andExpect(jsonPath("$.result.biomarkers[0].numericValue").value(162.0))
				.andExpect(jsonPath("$.result.biomarkers[0].unit").value("mg/dL"))
				.andExpect(jsonPath("$.result.biomarkers[0].referenceRangeText").value("<100"))
				.andExpect(jsonPath("$.result.biomarkers[0].refLow").value(nullValue()))
				.andExpect(jsonPath("$.result.biomarkers[0].refHigh").value(100.0))
				.andExpect(jsonPath("$.result.biomarkers[0].flag").value("HIGH"))
				.andExpect(jsonPath("$.result.biomarkers[0].biomarkerSlug")
						.value("ldl-cholesterol"));
	}

	@Test
	@DisplayName("a qualitative row carries nulls where the contract shows nulls")
	void qualitativeRowMatchesContract() throws Exception {
		UUID reportId = saveFinishedReport();

		// The second row in the contract's example: a value that renders as text rather than
		// on a range bar.
		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(jsonPath("$.result.biomarkers[1].rawValue").value("Negative"))
				.andExpect(jsonPath("$.result.biomarkers[1].numericValue").value(nullValue()))
				.andExpect(jsonPath("$.result.biomarkers[1].unit").value(nullValue()))
				.andExpect(jsonPath("$.result.biomarkers[1].referenceRangeText").value("Negative"))
				.andExpect(jsonPath("$.result.biomarkers[1].refLow").value(nullValue()))
				.andExpect(jsonPath("$.result.biomarkers[1].refHigh").value(nullValue()))
				.andExpect(jsonPath("$.result.biomarkers[1].flag").value("UNKNOWN"))
				.andExpect(jsonPath("$.result.biomarkers[1].biomarkerSlug").value(nullValue()));
	}

	@Test
	@DisplayName("values come back in report order")
	void returnsValuesInReportOrder() throws Exception {
		UUID reportId = saveFinishedReport();

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(jsonPath("$.result.biomarkers[0].testName").value("LDL Cholesterol"))
				.andExpect(jsonPath("$.result.biomarkers[1].testName").value("Urine Protein"));
	}

	@Test
	@DisplayName("a report with no collection date returns null for it")
	void handlesMissingCollectionDate() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);
		statuses.markProcessing(reportId);
		saver.saveDone(reportId, "Glucose 99 mg/dL 70-100", List.of(
				new ReportResultSaver.BiomarkerRow(0, "Glucose", "glucose", "glucose", "99",
						99.0, "mg/dL", "70-100", 70.0, 100.0, Flag.NORMAL)),
				"Everything is in range.", List.of(), null);

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(jsonPath("$.result.collectedOn").value(nullValue()))
				.andExpect(jsonPath("$.result.highlights.length()").value(0));
	}

	// --- never leaks the masked text -----------------------------------------

	@Test
	@DisplayName("the response never contains the stored report text")
	void neverReturnsMaskedText() throws Exception {
		UUID reportId = saveFinishedReport();

		String body = mockMvc.perform(get("/api/reports/" + reportId)
				.header("X-Session-Token", token))
				.andReturn().getResponse().getContentAsString();

		// report_text exists for phase 5's chat grounding. It is not part of this response,
		// and adding it would widen what a results page exposes for no reason.
		assertThat(body).doesNotContain("NORTHSIDE").doesNotContain("[NAME]");
	}

	// --- authorization -------------------------------------------------------

	@Test
	@DisplayName("another session's report is 404, not 403")
	void otherSessionGetsNotFound() throws Exception {
		UUID reportId = saveFinishedReport();
		String otherToken = fixtures.sessionToken();

		// CLAUDE.md rule 7: a 403 would confirm the id exists.
		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", otherToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
	}

	@Test
	@DisplayName("an unknown id is the same 404 as another session's report")
	void unknownIdGetsSameNotFound() throws Exception {
		mockMvc.perform(get("/api/reports/" + UUID.randomUUID())
				.header("X-Session-Token", token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
	}

	@Test
	@DisplayName("an expired report is 404 even before the retention job runs")
	void expiredReportIsNotFound() throws Exception {
		UUID reportId = fixtures.expiredReport(sessionId);

		// Reads filter on expires_at, so expired data is never served in the window before
		// cleanup (docs/dataflow.md 9.2).
		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
	}

	@Test
	@DisplayName("no token is 401 SESSION_INVALID")
	void missingTokenIsUnauthorized() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);

		mockMvc.perform(get("/api/reports/" + reportId))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("SESSION_INVALID"));
	}

	@Test
	@DisplayName("a malformed id is 400 VALIDATION_ERROR")
	void malformedIdIsBadRequest() throws Exception {
		mockMvc.perform(get("/api/reports/not-a-uuid").header("X-Session-Token", token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	// --- delete --------------------------------------------------------------

	@Test
	@DisplayName("deleting returns 204 and removes everything derived from the report")
	void deletesReportAndChildren() throws Exception {
		UUID reportId = saveFinishedReport();

		mockMvc.perform(delete("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("deleting a processing report is allowed")
	void deletesProcessingReport() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);
		statuses.markProcessing(reportId);

		// Allowed on purpose: the job's next conditional update finds nothing and it stops
		// without saving (LLD 7.4).
		mockMvc.perform(delete("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("deleting another session's report is 404 and deletes nothing")
	void cannotDeleteOtherSessionsReport() throws Exception {
		UUID reportId = saveFinishedReport();
		String otherToken = fixtures.sessionToken();

		mockMvc.perform(delete("/api/reports/" + reportId).header("X-Session-Token", otherToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));

		// Still there for its owner.
		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("deleting an unknown id is 404")
	void deletingUnknownIdIsNotFound() throws Exception {
		mockMvc.perform(delete("/api/reports/" + UUID.randomUUID())
				.header("X-Session-Token", token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
	}

	@Test
	@DisplayName("deleting without a token is 401")
	void deletingWithoutTokenIsUnauthorized() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);

		mockMvc.perform(delete("/api/reports/" + reportId))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("SESSION_INVALID"));
	}

	/** A DONE report matching the two rows in the contract's section 4.2 example. */
	private UUID saveFinishedReport() {
		UUID reportId = fixtures.pendingReport(sessionId);
		statuses.markProcessing(reportId);

		saver.saveDone(reportId,
				"NORTHSIDE COMMUNITY LABORATORY\nPatient Name: [NAME]\n"
						+ "LDL Cholesterol 162 mg/dL <100 H\nUrine Protein Negative Negative",
				List.of(
						new ReportResultSaver.BiomarkerRow(0, "LDL Cholesterol",
								"ldl cholesterol", "ldl-cholesterol", "162", 162.0, "mg/dL",
								"<100", null, 100.0, Flag.HIGH),
						new ReportResultSaver.BiomarkerRow(1, "Urine Protein",
								"urine protein", null, "Negative", null, null, "Negative",
								null, null, Flag.UNKNOWN)),
				"Your LDL cholesterol is high.",
				List.of("LDL Cholesterol is 162 mg/dL, above the reference range of <100."),
				LocalDate.of(2026, 9, 28));

		return reportId;
	}
}
