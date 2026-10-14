package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.saq.mediscan.analysis.ValidatedBiomarker;
import dev.saq.mediscan.eval.ExpectedResult.ExpectedBiomarker;
import dev.saq.mediscan.report.Flag;

/** Hand-made inputs, per {@code docs/phase-3-lld.md} section 9, item 3. */
class FlagMetricsTest {

	@Test
	void allCorrectFlagsScoreAccuracyOfOne() {
		var matches = List.of(
				scored("c1", "Glucose", Flag.NORMAL, Flag.NORMAL),
				scored("c1", "LDL", Flag.HIGH, Flag.HIGH));

		FlagMetrics.Summary summary = FlagMetrics.summarize(matches);

		assertThat(summary.flagAccuracy()).isEqualTo(1.0);
		assertThat(summary.dangerousErrors()).isEmpty();
	}

	@Test
	void expectedHighProducedNormalIsADangerousError() {
		var matches = List.of(scored("kidney-panel", "Creatinine", Flag.HIGH, Flag.NORMAL));

		FlagMetrics.Summary summary = FlagMetrics.summarize(matches);

		assertThat(summary.dangerousErrors()).hasSize(1);
		assertThat(summary.dangerousErrors().get(0).testName()).isEqualTo("Creatinine");
	}

	@Test
	void expectedNormalProducedHighIsAlsoADangerousError() {
		var matches = List.of(scored("c1", "Sodium", Flag.NORMAL, Flag.HIGH));

		FlagMetrics.Summary summary = FlagMetrics.summarize(matches);

		assertThat(summary.dangerousErrors()).hasSize(1);
	}

	@Test
	void unknownOnEitherSideIsNeverDangerous() {
		var matches = List.of(
				scored("c1", "A", Flag.HIGH, Flag.UNKNOWN),
				scored("c1", "B", Flag.UNKNOWN, Flag.HIGH),
				scored("c1", "C", Flag.UNKNOWN, Flag.NORMAL));

		FlagMetrics.Summary summary = FlagMetrics.summarize(matches);

		assertThat(summary.dangerousErrors()).isEmpty();
	}

	@Test
	void confusionMatrixCountsEveryExpectedProducedPair() {
		var matches = List.of(
				scored("c1", "A", Flag.HIGH, Flag.HIGH),
				scored("c1", "B", Flag.HIGH, Flag.NORMAL),
				scored("c1", "C", Flag.NORMAL, Flag.NORMAL));

		FlagMetrics.Summary summary = FlagMetrics.summarize(matches);

		assertThat(summary.confusionMatrix().get(Flag.HIGH).get(Flag.HIGH)).isEqualTo(1);
		assertThat(summary.confusionMatrix().get(Flag.HIGH).get(Flag.NORMAL)).isEqualTo(1);
		assertThat(summary.confusionMatrix().get(Flag.NORMAL).get(Flag.NORMAL)).isEqualTo(1);
	}

	@Test
	void unknownRateIsOutOfRowsTheKeySaysAreComputable() {
		var matches = List.of(
				// Computable per the key, but came back UNKNOWN: counts toward the rate.
				scored("c1", "A", Flag.HIGH, Flag.UNKNOWN),
				// Computable and correctly computed: does not count.
				scored("c1", "B", Flag.NORMAL, Flag.NORMAL),
				// The key itself says UNKNOWN: not part of the denominator at all.
				scored("c1", "C", Flag.UNKNOWN, Flag.UNKNOWN));

		FlagMetrics.Summary summary = FlagMetrics.summarize(matches);

		assertThat(summary.unknownRate()).isEqualTo(0.5);
	}

	@Test
	void rateIsZeroRatherThanNaNWithNoMatches() {
		FlagMetrics.Summary summary = FlagMetrics.summarize(List.of());

		assertThat(summary.flagAccuracy()).isEqualTo(0.0);
		assertThat(summary.unknownRate()).isEqualTo(0.0);
	}

	private static RowMatcher.ScoredMatch scored(String caseId, String testName, Flag expected,
			Flag produced) {
		var exp = new ExpectedBiomarker(testName, "1", null, null, 1.0, null, null, expected);
		var prod = new ValidatedBiomarker(0, testName, testName.toLowerCase(), null, "1", 1.0,
				null, null, null, null, produced);
		return new RowMatcher.ScoredMatch(caseId, new RowMatcher.Match(exp, prod));
	}
}
