package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.saq.mediscan.analysis.ValidatedBiomarker;
import dev.saq.mediscan.eval.ExpectedResult.ExpectedBiomarker;
import dev.saq.mediscan.report.Flag;

/** Hand-made inputs, per {@code docs/phase-3-lld.md} section 9, item 3 - never pipeline output. */
class RowMatcherTest {

	@Test
	void matchesOnNormalizedNameAndRawValue() {
		var expected = List.of(biomarker("LDL Cholesterol", "162", "mg/dL", "<100", 162.0, null,
				100.0, Flag.HIGH));
		var produced = List.of(validated(0, "LDL-Cholesterol", "ldl cholesterol", "162",
				"mg/dL", "<100", 162.0, null, 100.0, Flag.HIGH));

		RowMatcher.Result result = RowMatcher.match(expected, produced);

		assertThat(result.matches()).hasSize(1);
		assertThat(result.missed()).isEmpty();
		assertThat(result.extra()).isEmpty();
	}

	@Test
	void aDifferentRawValueIsNotAMatch() {
		var expected = List.of(biomarker("Glucose", "98", "mg/dL", "70-99", 98.0, 70.0, 99.0,
				Flag.NORMAL));
		var produced = List.of(validated(0, "Glucose", "glucose", "102", "mg/dL", "70-99", 102.0,
				70.0, 99.0, Flag.HIGH));

		RowMatcher.Result result = RowMatcher.match(expected, produced);

		assertThat(result.matches()).isEmpty();
		assertThat(result.missed()).hasSize(1);
		assertThat(result.extra()).hasSize(1);
	}

	@Test
	void repeatedTestWithDifferentValuesMatchesBothInOrder() {
		var expected = List.of(
				biomarker("Potassium", "6.2", "mmol/L", "3.5-5.1", 6.2, 3.5, 5.1, Flag.HIGH),
				biomarker("Potassium", "4.6", "mmol/L", "3.5-5.1", 4.6, 3.5, 5.1, Flag.NORMAL));
		var produced = List.of(
				validated(0, "Potassium", "potassium", "6.2", "mmol/L", "3.5-5.1", 6.2, 3.5, 5.1,
						Flag.HIGH),
				validated(1, "Potassium", "potassium", "4.6", "mmol/L", "3.5-5.1", 4.6, 3.5, 5.1,
						Flag.NORMAL));

		RowMatcher.Result result = RowMatcher.match(expected, produced);

		assertThat(result.matches()).hasSize(2);
		assertThat(result.matches().get(0).expected().rawValue()).isEqualTo("6.2");
		assertThat(result.matches().get(1).expected().rawValue()).isEqualTo("4.6");
	}

	@Test
	void anExpectedRowWithNoProducedRowIsMissed() {
		var expected = List.of(
				biomarker("TSH", "6.8", "mIU/L", "0.40-4.50", 6.8, 0.40, 4.50, Flag.HIGH));

		RowMatcher.Result result = RowMatcher.match(expected, List.of());

		assertThat(result.missed()).hasSize(1);
		assertThat(result.matches()).isEmpty();
	}

	@Test
	void aProducedRowWithNoExpectedCounterpartIsExtra() {
		var produced = List.of(
				validated(0, "Made Up Test", "made up test", "999", null, null, 999.0, null, null,
						Flag.UNKNOWN));

		RowMatcher.Result result = RowMatcher.match(List.of(), produced);

		assertThat(result.extra()).hasSize(1);
		assertThat(result.matches()).isEmpty();
	}

	@Test
	void onlyOneProducedRowIsClaimedEvenWithDuplicateRawValues() {
		// Two produced rows happen to share name+value; only one expected row should claim one
		// of them, leaving the other as extra rather than matching twice.
		var expected = List.of(biomarker("Sodium", "140", "mmol/L", "136-145", 140.0, 136.0,
				145.0, Flag.NORMAL));
		var produced = List.of(
				validated(0, "Sodium", "sodium", "140", "mmol/L", "136-145", 140.0, 136.0, 145.0,
						Flag.NORMAL),
				validated(1, "Sodium", "sodium", "140", "mmol/L", "136-145", 140.0, 136.0, 145.0,
						Flag.NORMAL));

		RowMatcher.Result result = RowMatcher.match(expected, produced);

		assertThat(result.matches()).hasSize(1);
		assertThat(result.extra()).hasSize(1);
	}

	private static ExpectedBiomarker biomarker(String testName, String rawValue, String unit,
			String rangeText, Double numeric, Double low, Double high, Flag flag) {
		return new ExpectedBiomarker(testName, rawValue, unit, rangeText, numeric, low, high, flag);
	}

	private static ValidatedBiomarker validated(int position, String testName, String norm,
			String rawValue, String unit, String rangeText, Double numeric, Double low,
			Double high, Flag flag) {
		return new ValidatedBiomarker(position, testName, norm, null, rawValue, numeric, unit,
				rangeText, low, high, flag);
	}
}
