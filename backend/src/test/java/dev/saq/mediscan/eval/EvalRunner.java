package dev.saq.mediscan.eval;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import dev.saq.mediscan.analysis.ReportJobFactory;
import dev.saq.mediscan.analysis.ValidatedBiomarker;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.llm.GeminiProvider;
import dev.saq.mediscan.report.BiomarkerEntity;
import dev.saq.mediscan.report.BiomarkerRepository;
import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.ReportStatus;
import dev.saq.mediscan.report.ReportSummaryRepository;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestPdfs;
import dev.saq.mediscan.report.Flag;

/**
 * Sends every case in {@code eval/cases} through the real pipeline with the real Gemini model
 * ({@code docs/phase-3-lld.md} section 5). {@code @Tag("eval")} keeps it out of
 * {@code ./mvnw verify}; {@code ./mvnw test -Peval} is the only way it runs.
 *
 * <p>Cases run one at a time, synchronously, on the calling thread -
 * {@code jobFactory.create(id, source).run()} is the same seam {@code ReportPipelineTest} uses
 * (approval point 2, item 1), not a raw call into {@code ReportJob}, so every production
 * failure-handling path (status writes, temp-file cleanup, MDC) runs exactly as it does for a
 * real upload.
 *
 * <p>Console output is case ids and counts only (backend/CLAUDE.md: no report content in logs).
 * The scored detail goes to {@code eval/results/<timestamp>/}.
 */
@SpringBootTest
@ActiveProfiles("eval")
@Import(EvalRunner.ProviderConfig.class)
@Tag("eval")
class EvalRunner extends PostgresTestBase {

	private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

	/** The literal marker {@code pdf-multi-page-cbc}'s {@code report.txt} splits pages on. */
	private static final String PAGE_BREAK = "[PAGE BREAK]";

	@Autowired
	ReportJobFactory jobFactory;

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

	@Autowired
	CachingLlmProvider caching;

	@Autowired
	RecordingLlmProvider provider;

	@Value("${eval.max-llm-calls:120}")
	int maxLlmCalls;

	@Value("${mediscan.upload.max-text-chars:50000}")
	int maxTextChars;

	/**
	 * Comma-separated case ids to run instead of the whole dataset, for a targeted
	 * investigation run. Blank (the default) runs every case, as a real scored run must.
	 */
	@Value("${eval.only-case-ids:}")
	String onlyCaseIdsCsv;

	@TempDir
	Path tempDir;

	private final List<String> forbiddenPhrases = SummaryChecks
			.loadForbiddenPhrases(Paths.get("..", "eval", "rules", "forbidden-phrases.txt"));

	@Test
	void runEvaluation() {
		Instant startedAt = Instant.now();
		List<EvalDatasetLoader.Entry> entries = filterToRequestedCases(EvalDatasetLoader.loadAll());
		List<CaseOutcome> outcomes = new ArrayList<>();
		List<EvalReportWriter.CaseReport> caseReports = new ArrayList<>();
		String stoppedEarlyReason = null;

		for (EvalDatasetLoader.Entry entry : entries) {
			EvalCase evalCase = entry.evalCase();

			if (caching.realCallCount() >= maxLlmCalls) {
				// The budget is checked only between cases: a case already in flight is
				// allowed to finish, so the final real-call count can land somewhat above this
				// number, not stop exactly at it.
				stoppedEarlyReason = "eval.max-llm-calls (" + maxLlmCalls
						+ ") reached before this case";
				log.info("stopping before case={}: {}", evalCase.id(), stoppedEarlyReason);
				break;
			}

			log.info("case accepted id={}", evalCase.id());
			provider.beginCase(evalCase.id());

			var sessionId = fixtures.session();
			SourceType sourceType = evalCase.format() == EvalCase.Format.PDF ? SourceType.PDF
					: SourceType.TEXT;
			var reportId = fixtures.pendingReport(sessionId, sourceType);

			jobFactory.create(reportId, buildSource(evalCase)).run();

			CaseOutcome outcome = readOutcome(evalCase, entry.expected(), reportId, sessionId);
			outcomes.add(outcome);
			log.info("case done id={} status={}", evalCase.id(), outcome.actualStatus());

			if (outcome.actualStatus() == ReportStatus.DONE) {
				caseReports.add(buildCaseReport(evalCase, entry.expected(), reportId, sessionId,
						outcome.producedRows()));
			}

			if (provider.endedInProviderFailure(evalCase.id())) {
				stoppedEarlyReason = "provider failure on case " + evalCase.id();
				log.warn("stopping after case={}: {}", evalCase.id(), stoppedEarlyReason);
				break;
			}
		}

		EvalReportWriter.write(EvalReportWriter.RunInfo.of(startedAt, caching, stoppedEarlyReason),
				outcomes, caseReports);

		// After the report is written, never before: a failing run still has to leave its
		// numbers on disk, or there is nothing to diagnose the failure from.
		//
		// Only for a complete run. A partial one (a case filter, or a stop on budget or
		// provider failure) has not measured enough to judge - scoring 4 of 31 cases against
		// a dataset-wide limit would fail or pass for reasons that say nothing about the
		// pipeline.
		if (stoppedEarlyReason == null && onlyCaseIdsCsv.isBlank()) {
			List<String> missed = ThresholdCheck.failures(
					ExtractionMetrics.summarize(outcomes),
					FlagMetrics.summarize(ExtractionMetrics.summarize(outcomes).matches()),
					caseReports);
			if (!missed.isEmpty()) {
				throw new AssertionError("eval/thresholds.json limits missed:"
						+ missed.stream().collect(
								java.util.stream.Collectors.joining("\n  - ", "\n  - ", "")));
			}
		}
	}

	private List<EvalDatasetLoader.Entry> filterToRequestedCases(List<EvalDatasetLoader.Entry> all) {
		if (onlyCaseIdsCsv == null || onlyCaseIdsCsv.isBlank()) {
			return all;
		}
		var requested = Arrays.stream(onlyCaseIdsCsv.split(",")).map(String::strip)
				.collect(java.util.stream.Collectors.toSet());
		List<EvalDatasetLoader.Entry> filtered =
				all.stream().filter(e -> requested.contains(e.evalCase().id())).toList();
		log.info("eval.only-case-ids set: running {} of {} cases", filtered.size(), all.size());
		return filtered;
	}

	private CaseOutcome readOutcome(EvalCase evalCase, ExpectedResult expected, java.util.UUID reportId,
			java.util.UUID sessionId) {

		ReportEntity report = reports.findById(reportId).orElseThrow();
		List<ValidatedBiomarker> rows = biomarkers
				.findOwnedBySession(reportId, sessionId, Instant.now())
				.stream()
				.map(EvalRunner::toValidated)
				.toList();

		return new CaseOutcome(evalCase, expected, report.getStatus(), report.getErrorCode(),
				report.getCollectedOn(), rows);
	}

	/** Masking-leak and summary-safety checks for one {@code DONE} case. */
	private EvalReportWriter.CaseReport buildCaseReport(EvalCase evalCase, ExpectedResult expected,
			java.util.UUID reportId, java.util.UUID sessionId, List<ValidatedBiomarker> rows) {

		String maskedText = jdbc.sql("select masked_text from report_text where report_id = :id")
				.param("id", reportId)
				.query(String.class)
				.optional()
				.orElse(null);

		String summaryText = null;
		List<String> highlights = List.of();
		var summary = summaries.findOwnedBySession(reportId, sessionId, Instant.now());
		if (summary.isPresent()) {
			summaryText = summary.get().getPatientSummary();
			highlights = summary.get().getHighlights();
		}

		List<MaskingLeakCheck.Haystack> haystacks = new ArrayList<>();
		haystacks.add(new MaskingLeakCheck.Haystack("report_text", maskedText));
		haystacks.add(new MaskingLeakCheck.Haystack("report_summary", summaryText));
		for (String highlight : highlights) {
			haystacks.add(new MaskingLeakCheck.Haystack("report_summary.highlight", highlight));
		}
		for (ValidatedBiomarker row : rows) {
			String joined = String.join(" ", nullToEmpty(row.testName()), nullToEmpty(row.rawValue()),
					nullToEmpty(row.unit()), nullToEmpty(row.referenceRangeText()));
			haystacks.add(new MaskingLeakCheck.Haystack("biomarker", joined));
		}
		int requestIndex = 0;
		for (var request : provider.requestsFor(evalCase.id())) {
			haystacks.add(new MaskingLeakCheck.Haystack(
					"llm_request[" + request.purpose().label() + "#" + requestIndex + "].system",
					request.systemPrompt()));
			haystacks.add(new MaskingLeakCheck.Haystack(
					"llm_request[" + request.purpose().label() + "#" + requestIndex + "].user",
					request.userContent()));
			requestIndex++;
		}

		List<MaskingLeakCheck.Leak> leaks = MaskingLeakCheck.findLeaks(expected.pii(), haystacks);

		int totalRows = rows.size();
		int highRows = (int) rows.stream().filter(r -> r.flag() == Flag.HIGH).count();
		int lowRows = (int) rows.stream().filter(r -> r.flag() == Flag.LOW).count();
		int flaggedRows = highRows + lowRows;

		List<SummaryChecks.InventedNumber> invented = SummaryChecks.findInventedNumbers(summaryText,
				highlights, rows, List.of(totalRows, flaggedRows, lowRows, highRows));
		List<SummaryChecks.ForbiddenPhraseHit> forbiddenHits =
				SummaryChecks.findForbiddenPhrases(summaryText, forbiddenPhrases);
		boolean mentionsClinician = SummaryChecks.mentionsClinician(summaryText);
		int sentenceCount = SummaryChecks.countSentences(summaryText);
		double readingGrade = ReadingLevel.fleschKincaidGrade(summaryText);

		Boolean injectionFollowed = null;
		if (expected.injection() != null && summaryText != null) {
			injectionFollowed = summaryText.toLowerCase(java.util.Locale.ROOT)
					.contains(expected.injection().toLowerCase(java.util.Locale.ROOT));
		}

		return new EvalReportWriter.CaseReport(evalCase.id(), summaryText, highlights, leaks,
				invented, forbiddenHits, mentionsClinician, sentenceCount, readingGrade,
				injectionFollowed);
	}

	private static String nullToEmpty(String text) {
		return text == null ? "" : text;
	}

	private JobSource buildSource(EvalCase evalCase) {
		String text = evalCase.id().equals("too-long") ? repeatPastLimit(evalCase.reportText())
				: evalCase.reportText();

		if (evalCase.format() == EvalCase.Format.TEXT) {
			return new JobSource.RawText(text);
		}

		byte[] pdf = renderPdf(evalCase);
		Path file = TestPdfs.toTempFile(tempDir, pdf);
		return new JobSource.PdfFile(file);
	}

	private byte[] renderPdf(EvalCase evalCase) {
		if (evalCase.id().equals("pdf-image-only")) {
			return TestPdfs.imageOnly();
		}
		if (evalCase.reportText().contains(PAGE_BREAK)) {
			List<List<String>> pages = Arrays.stream(evalCase.reportText().split(Pattern.quote(PAGE_BREAK)))
					.map(EvalRunner::linesOf)
					.toList();
			return TestPdfs.withPages(pages);
		}
		return TestPdfs.withText(linesOf(evalCase.reportText()));
	}

	private String repeatPastLimit(String block) {
		StringBuilder repeated = new StringBuilder(block);
		while (repeated.length() <= maxTextChars) {
			repeated.append('\n').append(block);
		}
		return repeated.toString();
	}

	private static List<String> linesOf(String text) {
		return Arrays.stream(text.split("\\R")).toList();
	}

	private static ValidatedBiomarker toValidated(BiomarkerEntity entity) {
		return new ValidatedBiomarker(entity.getPosition(), entity.getTestName(),
				entity.getTestNameNorm(), entity.getBiomarkerSlug(), entity.getRawValue(),
				entity.getNumericValue(), entity.getUnit(), entity.getReferenceRangeText(),
				entity.getRefLow(), entity.getRefHigh(), entity.getFlag());
	}

	/** Wires {@code RecordingLlmProvider(CachingLlmProvider(GeminiProvider))} (approval point 2, item 3). */
	@TestConfiguration
	static class ProviderConfig {

		/**
		 * A reference date after every date in the committed dataset (the latest is
		 * {@code pii-name-lookalike}'s 2026-10-20), so {@link
		 * dev.saq.mediscan.analysis.CollectedDateParser}'s "not in the future" check stays
		 * deterministic regardless of which real calendar day the run happens to execute on.
		 * {@code Clock.offset}, not {@code Clock.fixed}: it still advances in real time from
		 * this point, so duration tracking elsewhere (for example {@code ReportJobRunner}'s job
		 * timing) is not frozen at zero.
		 *
		 * <p>{@code @ConditionalOnMissingBean} on the production {@code ClockConfig} bean backs
		 * off in favour of this one - exactly the extension point its own javadoc describes.
		 * Production's date-plausibility logic and its existing test
		 * ({@code CollectedDateParserTest}, which builds its own fixed {@code Clock} directly
		 * and never touches this bean) are both unaffected.
		 */
		// @Primary is load-bearing, and its absence was silent (found 2026-10-20 by
		// header-footer-noise scoring a date miss on a real call). ClockConfig's bean is
		// @ConditionalOnMissingBean but is processed first, so both beans end up registered;
		// with two candidates and no @Primary, Spring falls back to matching the injection
		// point's parameter name - CollectedDateParser's is `clock`, which is ClockConfig's
		// bean name, not this one. The override registered and was never injected anywhere.
		@Bean
		@Primary
		Clock fixedEvalClock() {
			Instant referenceDate = Instant.parse("2027-01-01T00:00:00Z");
			return Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), referenceDate));
		}

		@Bean
		CachingLlmProvider cachingLlmProvider(GeminiProvider gemini,
				@Value("${eval.refresh-cache:false}") boolean refreshCache,
				@Value("${eval.delay-between-calls-ms:6000}") long delayBetweenCallsMs) {

			Path cacheDir = Paths.get("..", "eval", ".cache");
			return new CachingLlmProvider(gemini, cacheDir, refreshCache, delayBetweenCallsMs);
		}

		@Bean
		@Primary
		RecordingLlmProvider recordingLlmProvider(CachingLlmProvider caching) {
			return new RecordingLlmProvider(caching);
		}
	}
}
