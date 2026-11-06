package dev.saq.mediscan.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pass/fail limits in {@code eval/thresholds.json}, applied to a finished run
 * ({@code docs/phase-3-lld.md} section 7).
 *
 * <p>Advisory until that file exists, which is the point: decision E9 fixes the limits only
 * after a first real run, because targets chosen before any result is known get gamed. Once it
 * is there, a run that misses one fails and names every limit it missed - all of them, not just
 * the first, so one bad number cannot hide behind another.
 *
 * <p>Nothing here decides what a limit should be. That is the file's job, and changing it needs
 * a written reason and approval; see its own comment and {@code docs/phase-3-findings.md}.
 */
final class ThresholdCheck {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Path FILE = Path.of("..", "eval", "thresholds.json");

	private ThresholdCheck() {
	}

	/**
	 * @return every limit the run missed, worst-first in file order; empty when the run passes
	 *     or when no limits have been fixed yet
	 */
	static List<String> failures(ExtractionMetrics.Summary extraction, FlagMetrics.Summary flags,
			List<EvalReportWriter.CaseReport> caseReports) {

		if (!Files.isRegularFile(FILE)) {
			return List.of();
		}

		JsonNode limits = read();
		List<String> failures = new ArrayList<>();

		atLeast(failures, limits, "minRowRecall", "row recall", extraction.rowRecall());
		atLeast(failures, limits, "minRowPrecision", "row precision", extraction.rowPrecision());
		atLeast(failures, limits, "minFlagAccuracy", "flag accuracy", flags.flagAccuracy());
		atMostCount(failures, limits, "maxDangerousFlagErrors", "dangerous flag errors",
				flags.dangerousErrors().size());
		atMostCount(failures, limits, "maxInventedNumbers", "invented numbers",
				caseReports.stream().mapToInt(report -> report.invented().size()).sum());
		atMostRate(failures, limits, "maxFailureRateOnValidReports",
				"failure rate on valid reports", extraction.failureRateOnValidReports());

		// ADDRESS is the documented Phase 2 gap (LLD E6) and is reported on its own line
		// rather than counted here.
		atMostCount(failures, limits, "maxInScopeLeaks", "masking leaks of in-scope types",
				(int) caseReports.stream()
						.flatMap(report -> report.leaks().stream())
						.filter(leak -> leak.type() != ExpectedResult.PiiType.ADDRESS)
						.count());

		if (limits.path("injectionCaseMustPass").asBoolean(false)) {
			boolean followed = caseReports.stream()
					.anyMatch(report -> Boolean.TRUE.equals(report.injectionFollowed()));
			if (followed) {
				failures.add("injection case followed the injected instruction");
			}
		}

		return List.copyOf(failures);
	}

	private static void atLeast(List<String> failures, JsonNode limits, String key, String label,
			double actual) {

		JsonNode limit = limits.path(key);
		if (!limit.isMissingNode() && actual < limit.asDouble()) {
			failures.add(label + " " + percent(actual) + " is below the limit "
					+ percent(limit.asDouble()));
		}
	}

	private static void atMostRate(List<String> failures, JsonNode limits, String key,
			String label, double actual) {

		JsonNode limit = limits.path(key);
		if (!limit.isMissingNode() && actual > limit.asDouble()) {
			failures.add(label + " " + percent(actual) + " is above the limit "
					+ percent(limit.asDouble()));
		}
	}

	private static void atMostCount(List<String> failures, JsonNode limits, String key,
			String label, int actual) {

		JsonNode limit = limits.path(key);
		if (!limit.isMissingNode() && actual > limit.asInt()) {
			failures.add(label + ": " + actual + " is above the limit " + limit.asInt());
		}
	}

	private static String percent(double value) {
		return String.format("%.1f%%", value * 100);
	}

	private static JsonNode read() {
		try {
			return JSON.readTree(Files.readString(FILE));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
