package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.llm.FakeLlmProvider;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.report.BiomarkerRepository;
import dev.saq.mediscan.report.Flag;
import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.ReportStatus;
import dev.saq.mediscan.report.ReportSummaryRepository;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * The whole pipeline, from a queued job to stored results.
 *
 * <p>Runs the real job on the real executor against the fake provider, so the orchestration,
 * the status transitions, the masking, the validation and the final transaction are all
 * exercised together. The unit tests prove each stage; this proves they compose.
 *
 * <p>Awaitility rather than sleeping, per {@code backend/CLAUDE.md}: the job runs on a worker
 * thread, and polling for the status it reaches is both faster and not flaky.
 */
class ReportPipelineTest extends PostgresTestBase {

	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	@Autowired
	ReportJobFactory jobFactory;

	@Autowired
	FakeLlmProvider provider;

	@Autowired
	ReportRepository reports;

	@Autowired
	BiomarkerRepository biomarkers;

	@Autowired
	ReportSummaryRepository summaries;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	JdbcClient jdbc;

	private UUID sessionId;

	@BeforeEach
	void reset() {
		fixtures.clear();
		provider.reset();
		jdbc.sql("delete from llm_usage").update();
		sessionId = fixtures.session();
	}

	// --- the happy path ------------------------------------------------------

	@Test
	@DisplayName("a pasted report reaches DONE with validated values and a summary")
	void processesReportToDone() {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId);

		run(reportId, new JobSource.RawText(LIPID_TEXT));

		ReportEntity report = load(reportId);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.DONE);
		assertThat(report.getStartedAt()).isNotNull();
		assertThat(report.getFinishedAt()).isNotNull();
		assertThat(report.getCollectedOn()).isEqualTo(LocalDate.of(2026, 9, 28));

		// Flags computed in code from the printed ranges, not taken from the model.
		assertThat(biomarkers.findOwnedBySession(reportId, sessionId, Instant.now()))
				.extracting(marker -> marker.getTestName() + "=" + marker.getFlag())
				.containsExactly(
						"Total Cholesterol=HIGH",
						"HDL Cholesterol=LOW",
						"Triglycerides=NORMAL");

		assertThat(summaries.findOwnedBySession(reportId, sessionId, Instant.now()))
				.isPresent()
				.get()
				.satisfies(summary -> {
					assertThat(summary.getPatientSummary()).contains("cholesterol");
					// Only the two out-of-range markers get a highlight.
					assertThat(summary.getHighlights()).hasSize(2);
				});
	}

	@Test
	@DisplayName("the stored text is masked, and only stored on success")
	void storesOnlyMaskedText() {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId);

		run(reportId, new JobSource.RawText(LIPID_TEXT));

		String stored = jdbc.sql("select masked_text from report_text where report_id = :id")
				.param("id", reportId)
				.query(String.class)
				.single();

		assertThat(stored)
				.doesNotContain("Jane Q. Roe")
				.doesNotContain("A1234567")
				.doesNotContain("04/12/1985")
				.contains("[NAME]")
				// The values survived masking, which is what the support check depends on.
				.contains("238")
				.contains("Collected: 09/28/2026");
	}

	@Test
	@DisplayName("the model only ever sees masked text")
	void modelSeesOnlyMaskedText() {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId);

		run(reportId, new JobSource.RawText(LIPID_TEXT));

		// CLAUDE.md rule 1, asserted against every prompt the provider actually received.
		assertThat(provider.received()).isNotEmpty();
		for (var request : provider.received()) {
			assertThat(request.userContent())
					.doesNotContain("Jane Q. Roe")
					.doesNotContain("A1234567")
					.doesNotContain("04/12/1985");
		}
	}

	@Test
	@DisplayName("step 2 never receives the report text")
	void summaryStepSeesNoReportText() {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId);

		run(reportId, new JobSource.RawText(LIPID_TEXT));

		// CLAUDE.md rule 5: the summary is written from the validated list alone, which is
		// why it cannot contradict the flags.
		var summaryRequests = provider.received(LlmPurpose.SUMMARY);
		assertThat(summaryRequests).hasSize(1);
		assertThat(summaryRequests.get(0).userContent())
				.contains("<results>")
				.doesNotContain("Patient Name")
				.doesNotContain("[NAME]")
				.doesNotContain("Collected");
	}

	// --- failures ------------------------------------------------------------

	@Test
	@DisplayName("a report with no surviving values fails NO_RESULTS_FOUND")
	void failsWhenNothingSurvives() {
		// The model returned a value that is not in the text, so validation drops it.
		provider.respondWith(LlmPurpose.EXTRACT, new ModelExtraction(
				List.of(new ModelExtraction.ModelRow("Glucose", "999", "mg/dL", "70-100")), null));

		UUID reportId = fixtures.pendingReport(sessionId);
		run(reportId, new JobSource.RawText(LIPID_TEXT));

		assertThat(load(reportId).getStatus()).isEqualTo(ReportStatus.FAILED);
		assertThat(load(reportId).getErrorCode()).isEqualTo(ReportErrorCode.NO_RESULTS_FOUND);
	}

	@Test
	@DisplayName("a failed report stores no text, no values and no summary")
	void failedReportStoresNothing() {
		provider.respondWith(LlmPurpose.EXTRACT, new ModelExtraction(List.of(), null));

		UUID reportId = fixtures.pendingReport(sessionId);
		run(reportId, new JobSource.RawText(LIPID_TEXT));

		assertThat(load(reportId).getStatus()).isEqualTo(ReportStatus.FAILED);

		// The reason report_text is written in the final transaction rather than after
		// masking (docs/dataflow.md 4.2): a failure leaves nothing behind but its code.
		assertThat(count("report_text", reportId)).isZero();
		assertThat(count("biomarker", reportId)).isZero();
		assertThat(count("report_summary", reportId)).isZero();
	}

	@Test
	@DisplayName("unreadable text fails UNREADABLE before any model call")
	void failsOnUnreadableText() {
		UUID reportId = fixtures.pendingReport(sessionId);

		run(reportId, new JobSource.RawText("too short"));

		assertThat(load(reportId).getErrorCode()).isEqualTo(ReportErrorCode.UNREADABLE);
		// Extraction failed before the budget was touched.
		assertThat(provider.callCount()).isZero();
	}

	@Test
	@DisplayName("a provider outage fails LLM_UNAVAILABLE")
	void failsOnProviderOutage() {
		for (int i = 0; i < 4; i++) {
			provider.queue(LlmPurpose.EXTRACT, new FakeLlmProvider.Script.Transient());
		}

		UUID reportId = fixtures.pendingReport(sessionId);
		run(reportId, new JobSource.RawText(LIPID_TEXT));

		assertThat(load(reportId).getErrorCode()).isEqualTo(ReportErrorCode.LLM_UNAVAILABLE);
	}

	@Test
	@DisplayName("a report deleted mid-job writes nothing and is not revived")
	void deletedReportIsNotRevived() {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId);

		// Delete before the job runs, so the first conditional update finds nothing.
		reports.deleteById(reportId);

		jobFactory.create(reportId, new JobSource.RawText(LIPID_TEXT)).run();

		// LLD 7.4: the job stops quietly rather than resurrecting the row.
		assertThat(reports.findById(reportId)).isEmpty();
		assertThat(count("report_text", reportId)).isZero();
		assertThat(count("biomarker", reportId)).isZero();
	}

	@Test
	@DisplayName("an already-processed report is not processed twice")
	void doesNotReprocess() {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId);

		run(reportId, new JobSource.RawText(LIPID_TEXT));
		int callsAfterFirst = provider.callCount();

		// Submitting the same report again must not spend the budget a second time.
		jobFactory.create(reportId, new JobSource.RawText(LIPID_TEXT)).run();

		assertThat(provider.callCount()).isEqualTo(callsAfterFirst);
		assertThat(load(reportId).getStatus()).isEqualTo(ReportStatus.DONE);
		assertThat(count("report_text", reportId)).isEqualTo(1);
	}

	// --- model misbehaviour --------------------------------------------------

	@Test
	@DisplayName("an invented value is dropped and the rest of the report survives")
	void dropsInventedValues() {
		provider.respondWith(LlmPurpose.EXTRACT, new ModelExtraction(List.of(
				new ModelExtraction.ModelRow("Total Cholesterol", "238", "mg/dL", "<200"),
				// Not in the text at all.
				new ModelExtraction.ModelRow("Glucose", "99", "mg/dL", "70-100"),
				// In the text, but duplicated.
				new ModelExtraction.ModelRow("Total Cholesterol", "238", "mg/dL", "<200")),
				null));
		provider.respondWith(LlmPurpose.SUMMARY,
				new ModelSummary("Your cholesterol is high.", List.of("Total Cholesterol is high.")));

		UUID reportId = fixtures.pendingReport(sessionId);
		run(reportId, new JobSource.RawText(LIPID_TEXT));

		assertThat(load(reportId).getStatus()).isEqualTo(ReportStatus.DONE);
		assertThat(biomarkers.findOwnedBySession(reportId, sessionId, Instant.now()))
				.extracting(marker -> marker.getTestName())
				.containsExactly("Total Cholesterol");
	}

	@Test
	@DisplayName("a highlight about a normal marker is filtered out")
	void filtersUnjustifiedHighlights() {
		provider.respondWith(LlmPurpose.EXTRACT, new ModelExtraction(List.of(
				new ModelExtraction.ModelRow("Total Cholesterol", "238", "mg/dL", "<200"),
				new ModelExtraction.ModelRow("Triglycerides", "140", "mg/dL", "<150")), null));
		provider.respondWith(LlmPurpose.SUMMARY, new ModelSummary(
				"Your results are shown below.",
				List.of("Total Cholesterol is high.",
						// Triglycerides is NORMAL, so this would be a warning about a value
						// that is fine.
						"Triglycerides is high.",
						// Not on the report at all.
						"Your Glucose is high.")));

		UUID reportId = fixtures.pendingReport(sessionId);
		run(reportId, new JobSource.RawText(LIPID_TEXT));

		assertThat(summaries.findOwnedBySession(reportId, sessionId, Instant.now())
				.orElseThrow().getHighlights())
				.containsExactly("Total Cholesterol is high.");
	}

	@Test
	@DisplayName("no highlights at all when nothing is out of range")
	void noHighlightsWhenAllNormal() {
		provider.respondWith(LlmPurpose.EXTRACT, new ModelExtraction(List.of(
				new ModelExtraction.ModelRow("Triglycerides", "140", "mg/dL", "<150")), null));
		provider.respondWith(LlmPurpose.SUMMARY, new ModelSummary(
				"Everything is in range.", List.of("Triglycerides is high.")));

		UUID reportId = fixtures.pendingReport(sessionId);
		run(reportId, new JobSource.RawText(LIPID_TEXT));

		assertThat(summaries.findOwnedBySession(reportId, sessionId, Instant.now())
				.orElseThrow().getHighlights()).isEmpty();
	}

	@Test
	@DisplayName("a qualitative value is stored with no number and an UNKNOWN flag")
	void storesQualitativeValue() {
		String text = """
				Patient Name: Dolores Fenwick
				Collected: 02 Oct 2026
				TSH                             0.21        uIU/mL     0.45-4.50
				Thyroid Peroxidase Antibodies   Negative               Negative
				""";

		provider.respondWith(LlmPurpose.EXTRACT, new ModelExtraction(List.of(
				new ModelExtraction.ModelRow("TSH", "0.21", "uIU/mL", "0.45-4.50"),
				new ModelExtraction.ModelRow("Thyroid Peroxidase Antibodies", "Negative", null,
						"Negative")),
				"02 Oct 2026"));
		provider.respondWith(LlmPurpose.SUMMARY,
				new ModelSummary("Your TSH is low.", List.of("TSH is low.")));

		UUID reportId = fixtures.pendingReport(sessionId);
		run(reportId, new JobSource.RawText(text));

		var stored = biomarkers.findOwnedBySession(reportId, sessionId, Instant.now());
		assertThat(stored).hasSize(2);

		assertThat(stored.get(0).getFlag()).isEqualTo(Flag.LOW);
		assertThat(stored.get(0).getNumericValue()).isEqualTo(0.21);

		// Renders as text, not on a range bar (CLAUDE.md, "Qualitative values").
		assertThat(stored.get(1).getRawValue()).isEqualTo("Negative");
		assertThat(stored.get(1).getNumericValue()).isNull();
		assertThat(stored.get(1).getFlag()).isEqualTo(Flag.UNKNOWN);
	}

	// --- helpers -------------------------------------------------------------

	private static final String LIPID_TEXT = """
			NORTHSIDE COMMUNITY LABORATORY
			Patient Name: Jane Q. Roe
			DOB: 04/12/1985
			Patient ID: A1234567
			Collected: 09/28/2026

			Total Cholesterol           238         mg/dL       <200           H
			HDL Cholesterol             38          mg/dL       >40            L
			Triglycerides               140         mg/dL       <150
			""";

	private void scriptLipidPanel() {
		provider.respondWith(LlmPurpose.EXTRACT, new ModelExtraction(List.of(
				new ModelExtraction.ModelRow("Total Cholesterol", "238", "mg/dL", "<200"),
				new ModelExtraction.ModelRow("HDL Cholesterol", "38", "mg/dL", ">40"),
				new ModelExtraction.ModelRow("Triglycerides", "140", "mg/dL", "<150")),
				"09/28/2026"));

		provider.respondWith(LlmPurpose.SUMMARY, new ModelSummary(
				"Your total cholesterol is above the printed range of <200.",
				List.of("Total Cholesterol is above its range.",
						"HDL Cholesterol is below its range.")));
	}

	/** Runs the job inline and waits for the report to leave PENDING. */
	private void run(UUID reportId, JobSource source) {
		jobFactory.create(reportId, source).run();

		Awaitility.await().atMost(TIMEOUT).until(() -> {
			ReportEntity report = reports.findById(reportId).orElse(null);
			return report != null && report.getStatus() != ReportStatus.PENDING
					&& report.getStatus() != ReportStatus.PROCESSING;
		});
	}

	private ReportEntity load(UUID reportId) {
		return reports.findById(reportId).orElseThrow();
	}

	private int count(String table, UUID reportId) {
		return jdbc.sql("select count(*) from " + table + " where report_id = :id")
				.param("id", reportId)
				.query(Integer.class)
				.single();
	}
}
