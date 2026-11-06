package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.saq.mediscan.report.Flag;

/**
 * Flag logic, with every boundary case ({@code LLD} 11.5).
 *
 * <p>The boundaries are the whole point. A value sitting exactly on a bound is the case a
 * patient is most likely to query, and the difference between {@code <200} and {@code ≤200}
 * decides it. These are driven through the real parsers rather than hand-built {@link Range}
 * objects, so the test covers the path production actually takes.
 */
class FlagCalculatorTest {

	@ParameterizedTest(name = "{0} in {1} -> {2}")
	@CsvSource({
			// Inside.
			"4.0,   3.5-5.0,  NORMAL",
			"3.6,   3.5-5.0,  NORMAL",
			"4.9,   3.5-5.0,  NORMAL",
			// Exactly on an inclusive bound: a hyphenated range includes both ends.
			"3.5,   3.5-5.0,  NORMAL",
			"5.0,   3.5-5.0,  NORMAL",
			// Outside.
			"3.4,   3.5-5.0,  LOW",
			"5.1,   3.5-5.0,  HIGH",
			"0,     3.5-5.0,  LOW",
			"100,   3.5-5.0,  HIGH",
			// Real rows from the samples.
			"14.6,  13.5-17.5, NORMAL",
			"245,   150-400,   NORMAL",
			"0.21,  0.45-4.50, LOW",
			"1.74,  0.82-1.77, NORMAL",
	})
	@DisplayName("a plain value against a two-bound range")
	void flagsPlainValuesInPairs(String rawValue, String rangeText, Flag expected) {
		assertThat(flag(rawValue, rangeText)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} in {1} -> {2}")
	@CsvSource({
			// Exclusive upper bound: the bound itself is already out of range.
			"199,  <200,  NORMAL",
			"200,  <200,  HIGH",
			"201,  <200,  HIGH",
			"238,  <200,  HIGH",
			// Inclusive upper bound: the bound is the last acceptable value.
			"200,  <=200, NORMAL",
			"200,  ≤200,  NORMAL",
			"201,  ≤200,  HIGH",
			// A one-sided range cannot produce LOW.
			"0,    <200,  NORMAL",
			"140,  <150,  NORMAL",
			"172,  <100,  HIGH",
	})
	@DisplayName("a plain value against an upper bound, inclusive and exclusive")
	void flagsAgainstUpperBound(String rawValue, String rangeText, Flag expected) {
		assertThat(flag(rawValue, rangeText)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} in {1} -> {2}")
	@CsvSource({
			"61,   >60,   NORMAL",
			"60,   >60,   LOW",
			"59,   >60,   LOW",
			"60,   >=60,  NORMAL",
			"60,   ≥60,   NORMAL",
			"59,   ≥60,   LOW",
			// A one-sided range cannot produce HIGH.
			"1000, >60,   NORMAL",
			"38,   >40,   LOW",
			"41,   >40,   NORMAL",
	})
	@DisplayName("a plain value against a lower bound, inclusive and exclusive")
	void flagsAgainstLowerBound(String rawValue, String rangeText, Flag expected) {
		assertThat(flag(rawValue, rangeText)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} in {1} -> {2}")
	@CsvSource({
			// Conclusive: everything below 0.5 is below the range.
			"<0.5,   0.5-5.0,   LOW",
			"<0.4,   0.5-5.0,   LOW",
			"≤0.5,   0.5-5.0,   LOW",
			// Not conclusive: the true value could be either side of 0.45.
			"<0.5,   0.45-4.50, UNKNOWN",
			"<10,    0.5-5.0,   UNKNOWN",
			// Nothing to compare a lower-bounded value against.
			"<0.5,   <200,      UNKNOWN",
	})
	@DisplayName("a 'less than' value is flagged only when the whole interval is below range")
	void flagsLessThanValues(String rawValue, String rangeText, Flag expected) {
		assertThat(flag(rawValue, rangeText)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} in {1} -> {2}")
	@CsvSource({
			"'>200',  3.5-5.0,   HIGH",
			"'>5.0',  3.5-5.0,   HIGH",
			"'≥5.0',  3.5-5.0,   HIGH",
			"'>200',  <200,      HIGH",
			// Not conclusive: the true value could be inside or above.
			"'>4.0',  3.5-5.0,   UNKNOWN",
			"'>60',   >60,       UNKNOWN",
	})
	@DisplayName("a 'greater than' value is flagged only when it already reaches the top")
	void flagsGreaterThanValues(String rawValue, String rangeText, Flag expected) {
		assertThat(flag(rawValue, rangeText)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} with range {1} -> UNKNOWN")
	@CsvSource({
			// Qualitative value: nothing to compare.
			"Negative,  Negative",
			"Negative,  3.5-5.0",
			"Trace,     3.5-5.0",
			"Positive,  Negative",
			// Numeric value, unreadable range.
			"5.4,       Negative",
			"5.4,       'M: 13.5-17.5 F: 12-16'",
			"5.4,       'Adult 10-20 / Child 5-15'",
			"5.4,       'See comment'",
			"5.4,       '0.0 - 0.0'",
	})
	@DisplayName("a qualitative value or an unreadable range is always UNKNOWN")
	void unknownWhenNotComparable(String rawValue, String rangeText) {
		// UNKNOWN is always an acceptable answer: the value and its printed range are shown,
		// which is what the paper report says too.
		assertThat(flag(rawValue, rangeText)).isEqualTo(Flag.UNKNOWN);
	}

	@Test
	@DisplayName("a missing range is UNKNOWN, not NORMAL")
	void missingRangeIsUnknown() {
		// Defaulting to NORMAL would tell a patient a value is fine on no evidence at all.
		assertThat(FlagCalculator.calculate(ValueParser.parse("5.4"), Optional.empty()))
				.isEqualTo(Flag.UNKNOWN);
		assertThat(flag("5.4", null)).isEqualTo(Flag.UNKNOWN);
		assertThat(flag("5.4", "")).isEqualTo(Flag.UNKNOWN);
	}

	@Test
	@DisplayName("a null value is UNKNOWN rather than an exception")
	void nullValueIsUnknown() {
		assertThat(FlagCalculator.calculate(null, RangeParser.parse("3.5-5.0")))
				.isEqualTo(Flag.UNKNOWN);
	}

	@Test
	@DisplayName("a negative value is compared the same way as any other")
	void handlesNegativeValues() {
		assertThat(flag("-1", "-2 to 2")).isEqualTo(Flag.NORMAL);
		assertThat(flag("-3", "-2 to 2")).isEqualTo(Flag.LOW);
		assertThat(flag("3", "-2 to 2")).isEqualTo(Flag.HIGH);
	}

	@Test
	@DisplayName("there is no CRITICAL flag to reach")
	void noCriticalFlag() {
		// A deliberate omission (docs/plan.md 4.4): critical cutoffs cannot be derived from a
		// reference range, and inventing one would be the most dangerous thing this app could
		// do. Even a wildly out-of-range value is HIGH.
		assertThat(flag("9999", "3.5-5.0")).isEqualTo(Flag.HIGH);
		assertThat(Flag.values()).containsExactly(Flag.LOW, Flag.NORMAL, Flag.HIGH, Flag.UNKNOWN);
	}

	/** Through the real parsers, so the test covers the path production takes. */
	private static Flag flag(String rawValue, String rangeText) {
		return FlagCalculator.calculate(ValueParser.parse(rawValue), RangeParser.parse(rangeText));
	}
}
