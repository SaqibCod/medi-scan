package dev.saq.mediscan.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code eval/results/<timestamp>/}: {@code results.json} (everything, for tooling),
 * {@code report.md} (the human summary), and {@code summaries-for-review.md} (a human-read
 * sample) - {@code docs/phase-3-lld.md} section 8.
 */
public final class EvalReportWriter {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final DateTimeFormatter TIMESTAMP_FORMAT =
			DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private EvalReportWriter() {
	}

	/** The run header: {@code docs/phase-3-lld.md} section 8. */
	public record RunInfo(Instant startedAt, String gitCommit, String providerId, String model,
			int realCalls, int cacheHits, String stoppedEarlyReason) {

		public static RunInfo of(Instant startedAt, CachingLlmProvider caching,
				String stoppedEarlyReason) {

			return new RunInfo(startedAt, currentGitCommit(), caching.id(), caching.model(),
					caching.realCallCount(), caching.cacheHitCount(), stoppedEarlyReason);
		}

		private static String currentGitCommit() {
			try {
				Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
						.redirectErrorStream(true)
						.start();
				String output =
						new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
								.strip();
				process.waitFor();
				return output.isBlank() ? "unknown" : output;
			}
			catch (IOException | InterruptedException ex) {
				return "unknown";
			}
		}
	}

	/** Masking-leak and summary-safety results for one {@code DONE} case. */
	public record CaseReport(
			String caseId,
			String summaryText,
			List<String> highlights,
			List<MaskingLeakCheck.Leak> leaks,
			List<SummaryChecks.InventedNumber> invented,
			List<SummaryChecks.ForbiddenPhraseHit> forbiddenHits,
			boolean mentionsClinician,
			int sentenceCount,
			double readingGrade,
			Boolean injectionFollowed) {
	}

	public static void write(RunInfo info, List<CaseOutcome> outcomes, List<CaseReport> caseReports) {
		Path resultsDir = Paths.get("..", "eval", "results",
				TIMESTAMP_FORMAT.format(info.startedAt().atZone(java.time.ZoneOffset.UTC)));
		try {
			Files.createDirectories(resultsDir);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}

		ExtractionMetrics.Summary extraction = ExtractionMetrics.summarize(outcomes);
		FlagMetrics.Summary flags = FlagMetrics.summarize(extraction.matches());

		writeResultsJson(resultsDir, info, outcomes, extraction, flags, caseReports);
		writeReportMd(resultsDir, info, outcomes, extraction, flags, caseReports);
		writeSummariesForReview(resultsDir, outcomes, caseReports);
	}

	private static void writeResultsJson(Path dir, RunInfo info, List<CaseOutcome> outcomes,
			ExtractionMetrics.Summary extraction, FlagMetrics.Summary flags,
			List<CaseReport> caseReports) {

		var payload = new java.util.LinkedHashMap<String, Object>();
		payload.put("runInfo", info);
		payload.put("extraction", extraction);
		payload.put("flags", flags);
		payload.put("caseReports", caseReports);
		payload.put("outcomes", outcomes);

		writeFile(dir.resolve("results.json"), JSON.writeValueAsString(payload));
	}

	private static void writeReportMd(Path dir, RunInfo info, List<CaseOutcome> outcomes,
			ExtractionMetrics.Summary extraction, FlagMetrics.Summary flags,
			List<CaseReport> caseReports) {

		StringBuilder md = new StringBuilder();
		md.append("# Phase 3 evaluation report\n\n");
		md.append("- Date (UTC): ").append(info.startedAt()).append('\n');
		md.append("- Git commit: ").append(info.gitCommit()).append('\n');
		md.append("- Provider / model: ").append(info.providerId()).append(" / ")
				.append(info.model()).append('\n');
		md.append("- Cases run: ").append(outcomes.size()).append(" / ")
				.append(EvalDatasetLoader.loadAll().size()).append('\n');
		md.append("- Real model calls: ").append(info.realCalls()).append('\n');
		md.append("- Cache hits: ").append(info.cacheHits()).append('\n');
		if (info.stoppedEarlyReason() != null) {
			md.append("- **Stopped early:** ").append(info.stoppedEarlyReason()).append('\n');
		}
		md.append('\n');

		md.append("## Extraction metrics\n\n");
		md.append("| Metric | Value |\n|---|---|\n");
		pct(md, "Row recall", extraction.rowRecall());
		pct(md, "Row precision", extraction.rowPrecision());
		pct(md, "Unit accuracy", extraction.unitAccuracy());
		pct(md, "Range accuracy", extraction.rangeAccuracy());
		pct(md, "Numeric accuracy", extraction.numericAccuracy());
		pct(md, "Date accuracy", extraction.dateAccuracy());
		pct(md, "Status accuracy", extraction.statusAccuracy());
		pct(md, "Failure rate on valid reports", extraction.failureRateOnValidReports());
		md.append('\n');

		md.append("## Flag metrics (matched rows)\n\n");
		pct(md, "Flag accuracy", flags.flagAccuracy());
		pct(md, "Unknown rate (of computable rows)", flags.unknownRate());
		md.append("\n**Dangerous flag errors (target zero): ")
				.append(flags.dangerousErrors().size()).append("**\n\n");
		for (var error : flags.dangerousErrors()) {
			md.append("- case=").append(error.caseId()).append(" test=\"").append(error.testName())
					.append("\" expected=").append(error.expectedFlag()).append(" produced=")
					.append(error.producedFlag()).append('\n');
		}
		md.append('\n');

		md.append("## Masking leaks\n\n");
		var leaksByType = new java.util.TreeMap<ExpectedResult.PiiType, List<MaskingLeakCheck.Leak>>();
		for (CaseReport report : caseReports) {
			for (var leak : report.leaks()) {
				leaksByType.computeIfAbsent(leak.type(), t -> new ArrayList<>()).add(leak);
			}
		}
		for (var entry : leaksByType.entrySet()) {
			String tag = entry.getKey() == ExpectedResult.PiiType.ADDRESS
					? " (known gap, LLD E6 - not counted toward the zero-leak target)" : "";
			md.append("- **").append(entry.getKey()).append("**: ").append(entry.getValue().size())
					.append(" leak(s)").append(tag).append('\n');
		}
		if (leaksByType.isEmpty()) {
			md.append("No leaks found.\n");
		}
		md.append('\n');

		md.append("## Summary safety\n\n");
		int invented = caseReports.stream().mapToInt(c -> c.invented().size()).sum();
		int forbidden = caseReports.stream().mapToInt(c -> c.forbiddenHits().size()).sum();
		long clinicianMentions = caseReports.stream().filter(CaseReport::mentionsClinician).count();
		long sentenceInRange = caseReports.stream()
				.filter(c -> c.sentenceCount() >= 3 && c.sentenceCount() <= 5).count();
		double avgGrade = caseReports.stream().mapToDouble(CaseReport::readingGrade).average()
				.orElse(0.0);

		md.append("- Invented numbers (target zero): ").append(invented).append('\n');
		md.append("- Forbidden-phrase hits (each needs human judgment): ").append(forbidden)
				.append('\n');
		pct(md, "Mentions a doctor or nurse", caseReports.isEmpty() ? 0.0
				: (double) clinicianMentions / caseReports.size());
		pct(md, "Sentence count in [3, 5]", caseReports.isEmpty() ? 0.0
				: (double) sentenceInRange / caseReports.size());
		md.append("- Mean reading grade (approximate): ")
				.append(String.format(Locale.ROOT, "%.1f", avgGrade)).append('\n');
		caseReports.stream()
				.filter(c -> c.injectionFollowed() != null)
				.forEach(c -> md.append("- Injection case (`").append(c.caseId())
						.append("`) followed the injected instruction: ")
						.append(c.injectionFollowed()).append('\n'));
		md.append('\n');

		md.append("## First misses and extras (up to 30 each)\n\n");
		md.append("### Missed\n");
		extraction.missed().stream().limit(30).forEach(m -> md.append("- case=")
				.append(m.caseId()).append(" testName=\"").append(m.expected().testName())
				.append("\" rawValue=\"").append(m.expected().rawValue()).append("\"\n"));
		md.append("\n### Extra\n");
		extraction.extra().stream().limit(30).forEach(e -> md.append("- case=")
				.append(e.caseId()).append(" testName=\"").append(e.produced().testName())
				.append("\" rawValue=\"").append(e.produced().rawValue()).append("\"\n"));

		writeFile(dir.resolve("report.md"), md.toString());
	}

	private static void writeSummariesForReview(Path dir, List<CaseOutcome> outcomes,
			List<CaseReport> caseReports) {

		var noFlag = new ArrayList<CaseReport>();
		var mixed = new ArrayList<CaseReport>();
		CaseReport injectionCase = null;

		for (CaseReport report : caseReports) {
			if (report.injectionFollowed() != null) {
				injectionCase = report;
				continue;
			}
			boolean anyFlagged = outcomes.stream()
					.filter(o -> o.evalCase().id().equals(report.caseId()))
					.anyMatch(o -> o.producedRows().stream()
							.anyMatch(r -> r.flag() == dev.saq.mediscan.report.Flag.HIGH
									|| r.flag() == dev.saq.mediscan.report.Flag.LOW));
			(anyFlagged ? mixed : noFlag).add(report);
		}

		List<CaseReport> selected = new ArrayList<>();
		noFlag.stream().limit(2).forEach(selected::add);
		mixed.stream().limit(4).forEach(selected::add);
		if (injectionCase != null) {
			selected.add(injectionCase);
		}
		for (CaseReport extra : caseReports) {
			if (selected.size() >= 8) {
				break;
			}
			if (!selected.contains(extra)) {
				selected.add(extra);
			}
		}

		StringBuilder md = new StringBuilder();
		md.append("# Summaries for human review\n\n");
		md.append("Synthetic data only - safe to read and share. See `SECURITY.md`.\n\n");
		for (CaseReport report : selected) {
			md.append("## ").append(report.caseId()).append("\n\n");
			md.append(report.summaryText() == null ? "_(no summary - report did not finish DONE)_"
					: report.summaryText()).append("\n\n");
			if (!report.highlights().isEmpty()) {
				md.append("Highlights:\n");
				report.highlights().forEach(h -> md.append("- ").append(h).append('\n'));
				md.append('\n');
			}
		}

		writeFile(dir.resolve("summaries-for-review.md"), md.toString());
	}

	private static void pct(StringBuilder md, String label, double rate) {
		md.append("| ").append(label).append(" | ")
				.append(String.format(Locale.ROOT, "%.1f%%", rate * 100)).append(" |\n");
	}

	private static void writeFile(Path file, String content) {
		try {
			Files.writeString(file, content, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
