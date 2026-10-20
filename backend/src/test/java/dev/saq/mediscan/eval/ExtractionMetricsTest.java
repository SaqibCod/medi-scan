package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.saq.mediscan.analysis.ValidatedBiomarker;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.eval.ExpectedResult.ExpectedBiomarker;
import dev.saq.mediscan.report.Flag;
import dev.saq.mediscan.report.ReportStatus;

/** Hand-made {@link CaseOutcome}s, per {@code docs/phase-3-lld.md} section 9, item 3. */
class ExtractionMetricsTest {

	@Test
	void aPerfectDoneCaseScoresEverythingAtOne() {
		var expectedRow = row("Glucose", "98", "mg/dL", "70-99", 98.0, 70.0, 99.0, Flag.NORMAL);
		var producedRow = validated(0, "Glucose", "98", "mg/dL", "70-99", 98.0, 70.0, 99.0,
				Flag.NORMAL);

		CaseOutcome outcome = doneOutcome("perfect", LocalDate.of(2026, 1, 1),
				List.of(expectedRow), LocalDate.of(2026, 1, 1), List.of(producedRow));

		ExtractionMetrics.Summary summary = ExtractionMetrics.summarize(List.of(outcome));

		assertThat(summary.rowRecall()).isEqualTo(1.0);
		assertThat(summary.rowPrecision()).isEqualTo(1.0);
		assertThat(summary.unitAccuracy()).isEqualTo(1.0);
		assertThat(summary.rangeAccuracy()).isEqualTo(1.0);
		assertThat(summary.numericAccuracy()).isEqualTo(1.0);
		assertThat(summary.dateAccuracy()).isEqualTo(1.0);
		assertThat(summary.statusAccuracy()).isEqualTo(1.0);
		assertThat(summary.failureRateOnValidReports()).isEqualTo(0.0);
	}

	@Test
	void aMissedRowAndAnExtraRowCountSeparately() {
		var expectedRow = row("Potassium", "4.0", "mmol/L", "3.5-5.1", 4.0, 3.5, 5.1, Flag.NORMAL);
		// Produced row has a different raw value, so it neither matches nor explains the miss.
		var producedRow = validated(0, "Sodium", "140", "mmol/L", "136-145", 140.0, 136.0, 145.0,
				Flag.NORMAL);

		CaseOutcome outcome = doneOutcome("mismatch", null, List.of(expectedRow), null,
				List.of(producedRow));

		ExtractionMetrics.Summary summary = ExtractionMetrics.summarize(List.of(outcome));

		assertThat(summary.rowRecall()).isEqualTo(0.0);
		assertThat(summary.rowPrecision()).isEqualTo(0.0);
		assertThat(summary.missed()).hasSize(1);
		assertThat(summary.extra()).hasSize(1);
	}

	@Test
	void aCaseExpectedDoneThatActuallyFailedCountsTowardFailureRateOnly() {
		EvalCase evalCase = new EvalCase("should-have-worked", "t", true, EvalCase.Format.TEXT,
				List.of(), ReportStatus.DONE, null, "report text");
		ExpectedResult expected = new ExpectedResult(null,
				List.of(row("Glucose", "98", "mg/dL", "70-99", 98.0, 70.0, 99.0, Flag.NORMAL)),
				List.of(), null);
		CaseOutcome outcome = new CaseOutcome(evalCase, expected, ReportStatus.FAILED,
				ReportErrorCode.EXTRACTION_FAILED, null, List.of());

		ExtractionMetrics.Summary summary = ExtractionMetrics.summarize(List.of(outcome));

		assertThat(summary.failureRateOnValidReports()).isEqualTo(1.0);
		// No rows to score: the case never reached DONE, so it contributes nothing to recall's
		// denominator rather than being counted as "zero rows, all missed".
		assertThat(summary.totalExpectedRows()).isZero();
	}

	@Test
	void statusAccuracyCountsTheExactErrorCodeNotJustDoneVersusFailed() {
		EvalCase evalCase = new EvalCase("non-lab-text", "t", true, EvalCase.Format.TEXT, List.of(),
				ReportStatus.FAILED, ReportErrorCode.NO_RESULTS_FOUND, "a recipe");
		ExpectedResult expected = new ExpectedResult(null, List.of(), List.of(), null);
		// Failed, but with the wrong code - still FAILED, so a naive DONE/FAILED check would
		// call this a match.
		CaseOutcome wrongCode = new CaseOutcome(evalCase, expected, ReportStatus.FAILED,
				ReportErrorCode.EXTRACTION_FAILED, null, List.of());

		ExtractionMetrics.Summary summary = ExtractionMetrics.summarize(List.of(wrongCode));

		assertThat(summary.statusAccuracy()).isEqualTo(0.0);
	}

	@Test
	void rateIsZeroRatherThanNaNWhenADenominatorIsEmpty() {
		ExtractionMetrics.Summary summary = ExtractionMetrics.summarize(List.of());

		assertThat(summary.rowRecall()).isEqualTo(0.0);
		assertThat(summary.rowPrecision()).isEqualTo(0.0);
		assertThat(summary.dateAccuracy()).isEqualTo(0.0);
		assertThat(summary.failureRateOnValidReports()).isEqualTo(0.0);
	}

	private static CaseOutcome doneOutcome(String id, LocalDate expectedDate,
			List<ExpectedBiomarker> expectedRows, LocalDate actualDate,
			List<ValidatedBiomarker> producedRows) {

		EvalCase evalCase = new EvalCase(id, "t", true, EvalCase.Format.TEXT, List.of(),
				ReportStatus.DONE, null, "report text");
		ExpectedResult expected = new ExpectedResult(expectedDate, expectedRows, List.of(), null);
		return new CaseOutcome(evalCase, expected, ReportStatus.DONE, null, actualDate,
				producedRows);
	}

	private static ExpectedBiomarker row(String testName, String rawValue, String unit,
			String rangeText, Double numeric, Double low, Double high, Flag flag) {
		return new ExpectedBiomarker(testName, rawValue, unit, rangeText, numeric, low, high, flag);
	}

	private static ValidatedBiomarker validated(int position, String testName, String rawValue,
			String unit, String rangeText, Double numeric, Double low, Double high, Flag flag) {
		return new ValidatedBiomarker(position, testName,
				dev.saq.mediscan.analysis.TestNameNormalizer.normalize(testName), null, rawValue,
				numeric, unit, rangeText, low, high, flag);
	}
}
