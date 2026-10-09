package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The range table from {@code LLD} 11.4.
 *
 * <p>The flag a patient sees is computed from these bounds, so a misread range produces a
 * wrong flag - worse than no flag. Every case the parser is not certain about must return
 * empty, which is why the refusal cases below outnumber the accepting ones.
 */
class RangeParserTest {

	@ParameterizedTest(name = "{0} -> {1}..{2}")
	@CsvSource({
			"3.5-5.0,       3.5,  5.0",
			"'3.5 - 5.0',   3.5,  5.0",
			"3.5–5.0,       3.5,  5.0",
			"3.5—5.0,       3.5,  5.0",
			"'3.5 to 5.0',  3.5,  5.0",
			"13.5-17.5,     13.5, 17.5",
			"150-400,       150,  400",
			"'70 to 100',   70,   100",
			"'3.5 -5.0',    3.5,  5.0",
			"'0.45-4.50',   0.45, 4.50",
	})
	@DisplayName("reads a two-bound range, inclusive at both ends")
	void parsesPairs(String text, double low, double high) {
		Range range = RangeParser.parse(text).orElseThrow();

		assertThat(range.low()).isEqualTo(low);
		assertThat(range.high()).isEqualTo(high);
		assertThat(range.lowInclusive()).isTrue();
		assertThat(range.highInclusive()).isTrue();
	}

	@ParameterizedTest(name = "{0} -> high {1}, inclusive {2}")
	@CsvSource({
			"<200,      200,  false",
			"'< 200',   200,  false",
			"<=200,     200,  true",
			"≤200,      200,  true",
			"<150,      150,  false",
			"<100,      100,  false",
	})
	@DisplayName("reads an upper bound, and whether it is inclusive")
	void parsesUpperBounds(String text, double high, boolean inclusive) {
		Range range = RangeParser.parse(text).orElseThrow();

		assertThat(range.hasLow()).isFalse();
		assertThat(range.high()).isEqualTo(high);
		// This is what decides the flag for a value sitting exactly on the bound.
		assertThat(range.highInclusive()).isEqualTo(inclusive);
	}

	@ParameterizedTest(name = "{0} -> low {1}, inclusive {2}")
	@CsvSource({
			">60,       60,   false",
			"'> 60',    60,   false",
			">=60,      60,   true",
			"≥60,       60,   true",
			">40,       40,   false",
	})
	@DisplayName("reads a lower bound, and whether it is inclusive")
	void parsesLowerBounds(String text, double low, boolean inclusive) {
		Range range = RangeParser.parse(text).orElseThrow();

		assertThat(range.low()).isEqualTo(low);
		assertThat(range.hasHigh()).isFalse();
		assertThat(range.lowInclusive()).isEqualTo(inclusive);
	}

	@ParameterizedTest(name = "{0} -> {1}..{2}")
	@CsvSource({
			"'-2 to 2',     -2,  2",
			"'-0.5 to 0.5', -0.5, 0.5",
			"'-3 to -1',    -3,  -1",
	})
	@DisplayName("a negative lower bound is readable with the word 'to'")
	void parsesNegativeRangesWithTo(String text, double low, double high) {
		Range range = RangeParser.parse(text).orElseThrow();

		assertThat(range.low()).isEqualTo(low);
		assertThat(range.high()).isEqualTo(high);
	}

	@ParameterizedTest(name = "{0} is refused")
	@ValueSource(strings = { "-2 - 2", "-2-2", "-0.5-0.5" })
	@DisplayName("a negative lower bound with a hyphen is refused as ambiguous")
	void refusesAmbiguousNegativeRanges(String text) {
		// There is no reliable way to tell the sign from the separator here, and a range read
		// backwards would flag every value as out of range.
		assertThat(RangeParser.parse(text)).isEmpty();
	}

	@ParameterizedTest(name = "{0} is refused")
	@ValueSource(strings = {
			// Qualitative: there is no interval to compare against.
			"Negative", "Not detected", "Non-reactive", "See comment", "N/A", "--",
			"Normal", "No range established",
			// Depends on something the report does not tell us. Choosing one would be a
			// clinical judgement made by a regex (LLD 11.4).
			"M: 13.5-17.5 F: 12-16",
			"Male 13.5-17.5 Female 12.0-16.0",
			"Adult 10-20 / Child 5-15",
			"0-1 yr: 5-10, 2-5 yr: 6-12",
			// Not an interval. Both appear as placeholders for "no range", and treating
			// either as real would flag everything.
			"0.0 - 0.0", "5 - 3", "100-100", "10 to 2",
			// Unreadable.
			"3.5-", "-5.0", "to 5.0", "3.5 5.0", "<", ">=",
	})
	@DisplayName("refuses anything it cannot read as one certain interval")
	void refusesUnreadableRanges(String text) {
		assertThat(RangeParser.parse(text)).isEmpty();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t" })
	@DisplayName("handles null, empty and blank")
	void handlesMissingRange(String text) {
		assertThat(RangeParser.parse(text)).isEmpty();
	}

	@ParameterizedTest(name = "{0} -> {1}..{2}")
	@CsvSource({
			"'3.5-5.0 mg/dL',     3.5,  5.0",
			"'13.5-17.5 g/dL',    13.5, 17.5",
			"'4.0-11.0 x10^3/uL', 4.0,  11.0",
			"'40-70 %',           40,   70",
	})
	@DisplayName("ignores a trailing unit")
	void stripsTrailingUnit(String text, double low, double high) {
		// Labs often repeat the unit on the range. Without stripping it, the number count
		// check would see the "3" in "x10^3" and refuse the range.
		Range range = RangeParser.parse(text).orElseThrow();

		assertThat(range.low()).isEqualTo(low);
		assertThat(range.high()).isEqualTo(high);
	}

	@Test
	@DisplayName("refuses a range with three or more numbers")
	void refusesTooManyNumbers() {
		// The generic guard behind the sex- and age-dependent cases: whatever this is, it is
		// not one interval.
		assertThat(RangeParser.parse("1 2 3")).isEmpty();
		assertThat(RangeParser.parse("3.5-5.0 and 6.0-7.0")).isEmpty();
	}

	@Test
	@DisplayName("tolerates surrounding whitespace and tabs")
	void tolerantOfWhitespace() {
		assertThat(RangeParser.parse("  3.5-5.0  ")).isPresent();
		assertThat(RangeParser.parse("\t<200\t")).isPresent();
	}
}
