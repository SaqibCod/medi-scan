package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import dev.saq.mediscan.analysis.ReportJobFactory;
import dev.saq.mediscan.analysis.ValidatedBiomarker;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.extract.TesseractAvailable;
import dev.saq.mediscan.report.BiomarkerEntity;
import dev.saq.mediscan.report.BiomarkerRepository;
import dev.saq.mediscan.report.Flag;
import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.ReportStatus;
import dev.saq.mediscan.report.ReportSummaryRepository;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * The phase 4 gate: the same cases, read from a photograph, through the whole real pipeline
 * ({@code docs/phase-4-lld.md} 10.2 and 10.4).
 *
 * <p><strong>Why this exists alongside the tuning sweep.</strong> The sweep measures OCR text
 * quality and costs no quota, which is what made the parameter search affordable - but it cannot
 * answer the question the gate actually asks. "Did a misread digit get a confident flag" is a
 * property of the whole pipeline: OCR, then masking, then the model, then validation, then the
 * flag calculator. Only a real run produces it.
 *
 * <p><strong>Scored separately from the text cases, on purpose.</strong> OCR loses perhaps one
 * value in ten, so row recall on these cases is below what {@code eval/thresholds.json} demands
 * of text cases - and rightly so. Folding the two together would either fail the build for a
 * known property of photographs or quietly lower the bar for text. The limits applied here are
 * phase 4's own, and there are only two of them, both zero.
 *
 * <p>Skipped when Tesseract is missing. Run with {@code ./mvnw test -Peval}.
 */
@SpringBootTest
@ActiveProfiles("eval")
@Import(EvalRunner.ProviderConfig.class)
@Tag("eval")
class OcrEvalRunner extends PostgresTestBase {

	private static final Logger log = LoggerFactory.getLogger(OcrEvalRunner.class);

	/**
	 * Failure codes that mean "the model did not answer", not "the report could not be read".
	 *
	 * <p>The distinction is the whole point of the check that uses it. {@code UNREADABLE} and
	 * {@code NO_RESULTS_FOUND} are measurements — OCR or the pipeline reached a verdict about
	 * the document. These three mean the run never got far enough to measure anything, and a
	 * gate applied only to the cases that happened to get through would be reporting on a
	 * sample it does not name.
	 */
	private static final List<String> PROVIDER_FAILURES =
			List.of("LLM_UNAVAILABLE", "CAPACITY", "EXTRACTION_FAILED");

	/**
	 * The cases from LLD 10.1, as the tuning sweep used them.
	 *
	 * <p>One variant each, not six. Every case costs two real model calls, and the question here
	 * is whether the <em>pipeline</em> is safe on OCR text - which the clean scan answers at the
	 * most favourable end of OCR quality. If a dangerous flag appears even there, the rule is
	 * broken; the degraded variants are the sweep's job.
	 */
	private static final List<String> CASE_IDS = List.of(
			"lipid-clean-table",
			"cbc-clean-table",
			"cmp-clean-table",
			"thyroid-inline",
			"comparators",
			"number-formats",
			"boundary-values",
			"flags-as-asterisks",
			"pii-names-everywhere",
			"pii-formats");

	@TempDir
	Path tempDir;

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
	RecordingLlmProvider provider;

	@Autowired
	CachingLlmProvider caching;

	@Value("${mediscan.ocr.min-value-confidence:80}")
	double minValueConfidence;

	@Value("${eval.max-llm-calls:120}")
	int maxLlmCalls;

	/**
	 * Comma-separated case ids to run instead of all ten, the same knob {@code EvalRunner} has.
	 *
	 * <p>Added after a one-case investigation run (`-Deval.only-case-ids=too-long`) quietly
	 * started a full ten-case OCR run as well, because this class ignored the filter: twenty
	 * model calls and twelve minutes to check one thing that needed none of them. A targeted run
	 * has to stay targeted in both runners.
	 */
	@Value("${eval.only-case-ids:}")
	String onlyCaseIdsCsv;

	@Test
	void runOcrEvaluation() throws IOException {
		TesseractAvailable.orSkip();

		List<String> wanted = onlyCaseIdsCsv == null || onlyCaseIdsCsv.isBlank()
				? CASE_IDS
				: java.util.Arrays.stream(onlyCaseIdsCsv.split(","))
						.map(String::strip)
						.filter(CASE_IDS::contains)
						.toList();

		List<EvalDatasetLoader.Entry> cases = EvalDatasetLoader.loadAll().stream()
				.filter(entry -> wanted.contains(entry.evalCase().id()))
				.toList();

		// A filtered run is an investigation, not a gate, and saying so here is cheaper than
		// discovering it from a report that looks complete.
		org.junit.jupiter.api.Assumptions.assumeFalse(wanted.isEmpty(),
				"eval.only-case-ids names no OCR case, so there is nothing for this runner to do");
		assertThat(cases).hasSameSizeAs(wanted);
		if (wanted.size() < CASE_IDS.size()) {
			log.info("eval.only-case-ids set: running {} of {} OCR cases", wanted.size(),
					CASE_IDS.size());
		}

		List<RowMatcher.ScoredMatch> matches = new ArrayList<>();
		List<MaskingLeakCheck.Leak> leaks = new ArrayList<>();
		List<CaseLine> lines = new ArrayList<>();

		String stoppedEarly = null;
		for (EvalDatasetLoader.Entry entry : cases) {
			if (caching.realCallCount() >= maxLlmCalls) {
				// The free tier is small and a failed call still spends quota. Stopping here
				// leaves a short but honest run rather than a long one full of provider
				// errors, which would look like a clean sheet for the worst possible reason.
				stoppedEarly = "eval.max-llm-calls (" + maxLlmCalls + ") reached";
				log.info("stopping before case={}: {}", entry.evalCase().id(), stoppedEarly);
				break;
			}
			lines.add(runOne(entry, matches, leaks));
		}

		FlagMetrics.Summary flags = FlagMetrics.summarize(matches);
		String report = renderReport(lines, flags, leaks, stoppedEarly);
		log.info("OCR evaluation:\n{}", report);
		write(report);

		// A gate that passes because nothing was measured is worse than no gate: it is a green
		// tick over an untested pipeline. Both limits below are satisfied trivially by a run
		// where every case failed, so the run has to prove it produced results first.
		assertThat(matches)
				.as("no OCR case produced a single matched row - the run measured nothing, so "
						+ "the limits below would pass for the wrong reason. Check that the "
						+ "model is answering and that tesseract is present")
				.isNotEmpty();

		// And the subtler version of the same mistake: a run where the model was unavailable
		// for *some* cases still produces matched rows, so the assertion above passes and the
		// limits are then applied to whatever subset happened to get through. "Zero dangerous
		// flag errors across three of ten cases" is not the gate, and reporting it as though it
		// were is the misleading result this check exists to prevent.
		List<String> notMeasured = lines.stream()
				.filter(line -> PROVIDER_FAILURES.stream().anyMatch(line.outcome()::contains))
				.map(CaseLine::caseId)
				.toList();
		assertThat(notMeasured)
				.as("the model did not answer for these cases, so they were never scored - the "
						+ "gate would be reporting on a silently smaller sample. Re-run when the "
						+ "provider is healthy (docs/phase-4-findings.md item 6)")
				.isEmpty();
		assertThat(stoppedEarly)
				.as("the run stopped before every case was attempted, so the gate covers only "
						+ "part of the dataset")
				.isNull();

		// LLD 10.4, and only these two. Everything else in this run is reported, not gated:
		// OCR loses values, and a recall limit here would fail for a property of photographs.
		assertThat(flags.dangerousErrors())
				.as("a value OCR misread must never get a confident flag - the worst failure "
						+ "this application can have (CLAUDE.md rule 4)")
				.isEmpty();
		assertThat(leaks)
				.as("personal data planted in a case must not survive OCR and masking into "
						+ "storage or a prompt")
				.isEmpty();
	}

	/** Renders one case as a clean scan, runs it, and scores what came out. */
	private CaseLine runOne(EvalDatasetLoader.Entry entry, List<RowMatcher.ScoredMatch> matches,
			List<MaskingLeakCheck.Leak> leaks) throws IOException {

		EvalCase evalCase = entry.evalCase();
		log.info("ocr case accepted id={}", evalCase.id());
		provider.beginCase(evalCase.id());

		OcrCaseRenderer.Rendered rendered = OcrCaseRenderer.render(evalCase.reportText(),
				OcrCaseRenderer.Variant.CLEAN_SCAN, OcrCaseRenderer.Typeface.MONOSPACED,
				evalCase.id().hashCode());
		Path file = Files.createTempFile(tempDir, "ocr-eval-", ".png");
		Files.write(file, rendered.bytes());

		UUID sessionId = fixtures.session();
		UUID reportId = fixtures.pendingReport(sessionId, SourceType.IMAGE);

		Instant startedAt = Instant.now();
		jobFactory.create(reportId, new JobSource.ImageFile(file, JobSource.ImageKind.PNG)).run();
		long millis = java.time.Duration.between(startedAt, Instant.now()).toMillis();

		ReportEntity report = reports.findById(reportId).orElseThrow();
		log.info("ocr case done id={} status={} textSource={}", evalCase.id(),
				report.getStatus(), report.getTextSource());

		if (report.getStatus() != ReportStatus.DONE) {
			// The code matters, not just the status. A report that failed because it could not
			// be read is a measurement; one that failed because the model was unavailable is a
			// missing measurement wearing the same clothes.
			String outcome = report.getErrorCode() == null
					? report.getStatus().name()
					: report.getStatus().name() + " " + report.getErrorCode().name();
			return new CaseLine(evalCase.id(), outcome, 0,
					entry.expected().biomarkers().size(), 0, 0, millis);
		}

		List<ValidatedBiomarker> produced = biomarkers
				.findOwnedBySession(reportId, sessionId, Instant.now())
				.stream()
				.map(OcrEvalRunner::toValidated)
				.toList();

		RowMatcher.Result matched = RowMatcher.match(entry.expected().biomarkers(), produced);
		for (RowMatcher.Match match : matched.matches()) {
			matches.add(new RowMatcher.ScoredMatch(evalCase.id(), match));
		}

		leaks.addAll(MaskingLeakCheck.findLeaks(entry.expected().pii(),
				haystacks(reportId, sessionId, produced, evalCase.id())));

		// The cost of the confidence rule, on this case: rows the reader is asked to check
		// rather than rows we were willing to flag.
		int unclear = (int) produced.stream().filter(row -> row.flag() == Flag.UNKNOWN).count();

		return new CaseLine(evalCase.id(), report.getTextSource().name(), matched.matches().size(),
				entry.expected().biomarkers().size(), produced.size(), unclear, millis);
	}

	/** Every place a planted string must not appear. */
	private List<MaskingLeakCheck.Haystack> haystacks(UUID reportId, UUID sessionId,
			List<ValidatedBiomarker> rows, String caseId) {

		List<MaskingLeakCheck.Haystack> haystacks = new ArrayList<>();

		haystacks.add(new MaskingLeakCheck.Haystack("report_text",
				jdbc.sql("select masked_text from report_text where report_id = :id")
						.param("id", reportId).query(String.class).optional().orElse(null)));

		summaries.findOwnedBySession(reportId, sessionId, Instant.now()).ifPresent(summary -> {
			haystacks.add(new MaskingLeakCheck.Haystack("report_summary",
					summary.getPatientSummary()));
			for (String highlight : summary.getHighlights()) {
				haystacks.add(new MaskingLeakCheck.Haystack("report_summary.highlight",
						highlight));
			}
		});

		for (ValidatedBiomarker row : rows) {
			haystacks.add(new MaskingLeakCheck.Haystack("biomarker", String.join(" ",
					nullToEmpty(row.testName()), nullToEmpty(row.rawValue()),
					nullToEmpty(row.unit()), nullToEmpty(row.referenceRangeText()))));
		}

		int index = 0;
		for (var request : provider.requestsFor(caseId)) {
			haystacks.add(new MaskingLeakCheck.Haystack(
					"llm_request[" + request.purpose().label() + "#" + index + "].system",
					request.systemPrompt()));
			haystacks.add(new MaskingLeakCheck.Haystack(
					"llm_request[" + request.purpose().label() + "#" + index + "].user",
					request.userContent()));
			index++;
		}

		return haystacks;
	}

	// --- output --------------------------------------------------------------

	/** One case's figures. Counts only - no values, no names. */
	private record CaseLine(String caseId, String outcome, int matchedRows, int expectedRows,
			int producedRows, int unclearRows, long millis) {
	}

	private String renderReport(List<CaseLine> lines, FlagMetrics.Summary flags,
			List<MaskingLeakCheck.Leak> leaks, String stoppedEarly) {

		StringBuilder out = new StringBuilder();
		out.append("# OCR evaluation (phase 4, LLD 10.2)\n\n");
		out.append("Variant: clean-scan PNG, monospace, 300 DPI. ")
				.append("Threshold: min-value-confidence ").append((int) minValueConfidence)
				.append(".\n\n");
		if (stoppedEarly != null) {
			out.append("> **Partial run: ").append(stoppedEarly)
					.append(".** The cases below are the ones that ran; the rest were not "
							+ "measured and nothing here should be read as covering them.\n\n");
		}

		out.append("| case | textSource | matched / expected | produced | unclear | ms |\n");
		out.append("|---|---|---:|---:|---:|---:|\n");
		for (CaseLine line : lines) {
			out.append("| ").append(line.caseId())
					.append(" | ").append(line.outcome())
					.append(" | ").append(line.matchedRows()).append(" / ")
					.append(line.expectedRows())
					.append(" | ").append(line.producedRows())
					.append(" | ").append(line.unclearRows())
					.append(" | ").append(line.millis())
					.append(" |\n");
		}

		int expected = lines.stream().mapToInt(CaseLine::expectedRows).sum();
		int matched = lines.stream().mapToInt(CaseLine::matchedRows).sum();
		int produced = lines.stream().mapToInt(CaseLine::producedRows).sum();
		int unclear = lines.stream().mapToInt(CaseLine::unclearRows).sum();

		List<String> notMeasured = lines.stream()
				.filter(line -> PROVIDER_FAILURES.stream().anyMatch(line.outcome()::contains))
				.map(CaseLine::caseId)
				.toList();

		out.append("\n## Gate (LLD 10.4)\n\n");
		// Said before the numbers, not after. A reader who sees two zeroes and stops reading
		// has to see this first, or the table has misled them. Three ways a run can fail to be
		// a gate, and a filtered run is one of them - "0 dangerous flag errors" over one case
		// someone selected by hand reads exactly like the real thing otherwise.
		if (!notMeasured.isEmpty()) {
			out.append("> **NOT A VALID GATE RESULT.** The model did not answer for ")
					.append(notMeasured.size()).append(" case(s): ")
					.append(String.join(", ", notMeasured))
					.append(". The figures below describe only the cases that were scored, "
							+ "which is not what the gate asks about.\n\n");
		}
		else if (stoppedEarly != null) {
			out.append("> **NOT A VALID GATE RESULT.** ").append(stoppedEarly)
					.append(", so the dataset was not finished.\n\n");
		}
		else if (lines.size() < CASE_IDS.size()) {
			out.append("> **NOT A VALID GATE RESULT.** This was a filtered run "
					+ "(`eval.only-case-ids`) covering ").append(lines.size()).append(" of ")
					.append(CASE_IDS.size()).append(" cases - an investigation, not the gate.\n\n");
		}
		out.append("| | |\n|---|---|\n");
		out.append("| Dangerous flag errors | **").append(flags.dangerousErrors().size())
				.append("** |\n");
		out.append("| In-scope leaks | **").append(leaks.size()).append("** |\n");
		out.append("| Cases scored | ").append(lines.size() - notMeasured.size())
				.append(" of ").append(CASE_IDS.size()).append(" |\n");

		out.append("\n## Reported, not gated\n\n");
		out.append("| | |\n|---|---|\n");
		out.append("| Row recall | ").append(percent(matched, expected))
				.append(" (").append(matched).append(" / ").append(expected).append(") |\n");
		out.append("| Flag accuracy on matched rows | ")
				.append(String.format(Locale.ROOT, "%.0f%%", flags.flagAccuracy() * 100))
				.append(" |\n");
		out.append("| Unclear rate | ").append(percent(unclear, produced))
				.append(" (").append(unclear).append(" / ").append(produced)
				.append(" produced rows) |\n");
		out.append("| Mean time per report | ")
				.append(lines.isEmpty() ? 0
						: lines.stream().mapToLong(CaseLine::millis).sum() / lines.size())
				.append(" ms |\n");

		if (!flags.dangerousErrors().isEmpty()) {
			out.append("\n## Dangerous flag errors\n\n");
			for (FlagMetrics.DangerousError error : flags.dangerousErrors()) {
				out.append("- ").append(error.caseId()).append(": expected ")
						.append(error.expectedFlag()).append(", produced ")
						.append(error.producedFlag()).append('\n');
			}
		}
		if (!leaks.isEmpty()) {
			out.append("\n## Leaks\n\n");
			for (MaskingLeakCheck.Leak leak : leaks) {
				// The type and the place, never the string itself.
				out.append("- ").append(leak.type()).append(" in ").append(leak.where())
						.append('\n');
			}
		}

		return out.toString();
	}

	private static String percent(int part, int whole) {
		return whole == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * part / whole);
	}

	private static void write(String report) {
		Path directory = Paths.get("..", "eval", "results");
		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
				.withZone(java.time.ZoneOffset.UTC).format(Instant.now());
		try {
			Files.createDirectories(directory);
			Path file = directory.resolve("ocr-eval-" + stamp + ".md");
			Files.writeString(file, report, StandardCharsets.UTF_8);
			log.info("wrote {}", file.toAbsolutePath().normalize());
		}
		catch (IOException ex) {
			log.warn("could not write the OCR evaluation: {}", ex.getClass().getSimpleName());
		}
	}

	private static String nullToEmpty(String text) {
		return text == null ? "" : text;
	}

	private static ValidatedBiomarker toValidated(BiomarkerEntity entity) {
		return new ValidatedBiomarker(entity.getPosition(), entity.getTestName(),
				entity.getTestNameNorm(), entity.getBiomarkerSlug(), entity.getRawValue(),
				entity.getNumericValue(), entity.getUnit(), entity.getReferenceRangeText(),
				entity.getRefLow(), entity.getRefHigh(), entity.getFlag());
	}
}
