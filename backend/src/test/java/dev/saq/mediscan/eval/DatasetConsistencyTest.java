package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.report.Flag;
import dev.saq.mediscan.report.ReportStatus;

/**
 * Checks the committed dataset against its own authoring rules ({@code docs/phase-3-lld.md}
 * section 9, item 1), with no model call and no database. Runs in every {@code ./mvnw verify}.
 *
 * <p>Three of the LLD's rules never reach an assertion here because {@link EvalDatasetLoader}
 * refuses to load a case that breaks them: every case has its three files as valid JSON, every
 * case is marked {@code synthetic}, and the folder name matches the case id. A flag value
 * outside {@code LOW}/{@code NORMAL}/{@code HIGH}/{@code UNKNOWN} - in particular {@code
 * CRITICAL}, which the {@link Flag} enum does not have - fails to parse for the same reason.
 */
class DatasetConsistencyTest {

	private final List<EvalDatasetLoader.Entry> entries = EvalDatasetLoader.loadAll();

	@Test
	void caseIdsAreUnique() {
		Set<String> ids = new HashSet<>();
		for (EvalDatasetLoader.Entry entry : entries) {
			assertThat(ids.add(entry.evalCase().id()))
					.as("duplicate case id " + entry.evalCase().id())
					.isTrue();
		}
	}

	@Test
	void expectedStatusIsDoneOrFailed() {
		for (EvalDatasetLoader.Entry entry : entries) {
			assertThat(entry.evalCase().expectedStatus())
					.as("case " + entry.evalCase().id())
					.isIn(ReportStatus.DONE, ReportStatus.FAILED);
		}
	}

	@Test
	void failedCasesCarryAnErrorCodeAndNoBiomarkers() {
		for (EvalDatasetLoader.Entry entry : entries) {
			boolean failed = entry.evalCase().expectedStatus() == ReportStatus.FAILED;
			assertThat(entry.evalCase().expectedErrorCode() != null)
					.as("case " + entry.evalCase().id() + " expectedErrorCode presence")
					.isEqualTo(failed);
			if (failed) {
				assertThat(entry.expected().biomarkers())
						.as("case " + entry.evalCase().id())
						.isEmpty();
			}
		}
	}

	@Test
	void everyExpectedRowAppearsInTheReportText() {
		for (EvalDatasetLoader.Entry entry : entries) {
			String text = entry.evalCase().reportText();
			for (ExpectedResult.ExpectedBiomarker row : entry.expected().biomarkers()) {
				assertThat(text)
						.as("case %s testName \"%s\"", entry.evalCase().id(), row.testName())
						.contains(row.testName());
				assertThat(text)
						.as("case %s rawValue \"%s\"", entry.evalCase().id(), row.rawValue())
						.contains(row.rawValue());
			}
		}
	}

	@Test
	void everyPlantedStringAppearsInTheReportText() {
		for (EvalDatasetLoader.Entry entry : entries) {
			String text = entry.evalCase().reportText();
			for (ExpectedResult.PlantedPii planted : entry.expected().pii()) {
				assertThat(text)
						.as("case %s planted %s \"%s\"", entry.evalCase().id(), planted.type(),
								planted.text())
						.contains(planted.text());
			}
		}
	}

	@Test
	void plantedStringsDoNotOverlapBiomarkerFields() {
		for (EvalDatasetLoader.Entry entry : entries) {
			List<String> markerFields = entry.expected().biomarkers().stream()
					.flatMap(row -> Stream.of(row.testName(), row.rawValue(), row.unit(),
							row.referenceRangeText()))
					.filter(Objects::nonNull)
					.toList();

			for (ExpectedResult.PlantedPii planted : entry.expected().pii()) {
				for (String field : markerFields) {
					boolean overlaps = field.contains(planted.text()) || planted.text().contains(field);
					assertThat(overlaps)
							.as("case %s: planted %s \"%s\" overlaps biomarker field \"%s\"",
									entry.evalCase().id(), planted.type(), planted.text(), field)
							.isFalse();
				}
			}
		}
	}

	@Test
	void flagMixIsWithinTheLooseBandOfTheTarget() {
		Map<Flag, Long> counts = new HashMap<>();
		long total = 0;
		for (EvalDatasetLoader.Entry entry : entries) {
			for (ExpectedResult.ExpectedBiomarker row : entry.expected().biomarkers()) {
				counts.merge(row.flag(), 1L, (a, b) -> a + b);
				total++;
			}
		}

		// The band only means something once most of the dataset exists; the first commits
		// add only a handful of cases, which a ±15-point band would reject for no reason.
		Assumptions.assumeTrue(total >= 100,
				"flag-mix band is only meaningful once most of the dataset exists");

		// LLD section 4.2 rule 7 targets: NORMAL 55%, HIGH 20%, LOW 15%, UNKNOWN 10%.
		assertBand(counts, Flag.NORMAL, total, 55, 15);
		assertBand(counts, Flag.HIGH, total, 20, 15);
		assertBand(counts, Flag.LOW, total, 15, 15);
		assertBand(counts, Flag.UNKNOWN, total, 10, 15);
	}

	private static void assertBand(Map<Flag, Long> counts, Flag flag, long total,
			int targetPercent, int bandPoints) {

		double actualPercent = 100.0 * counts.getOrDefault(flag, 0L) / total;
		assertThat(actualPercent)
				.as("%s share of expected rows", flag)
				.isBetween((double) (targetPercent - bandPoints), (double) (targetPercent + bandPoints));
	}
}
