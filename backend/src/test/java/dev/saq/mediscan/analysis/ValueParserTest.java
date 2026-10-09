package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The value table from {@code LLD} 11.3, plus the cases that table implies.
 *
 * <p>The parser is deliberately strict, and the negative cases are the important half.
 * Anything it does not positively recognise becomes "not numeric", which shows as text and
 * flags {@code UNKNOWN}. Showing a patient no number is a disclosed limitation; showing them
 * a number the lab never printed is not.
 */
class ValueParserTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"5.4,      5.4",
			"162,      162",
			"0,        0",
			"-1.5,     -1.5",
			"+3,       3",
			"99.99,    99.99",
			"0.001,    0.001",
	})
	@DisplayName("parses a plain number")
	void parsesPlainNumbers(String raw, double expected) {
		ParsedValue value = ValueParser.parse(raw);

		assertThat(value.number()).isEqualTo(expected);
		assertThat(value.comparator()).isEqualTo(Comparator.NONE);
		// A plain number is the only kind that becomes a trend point.
		assertThat(value.storedNumber()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"'1,200',    1200",
			"'1,200.5',  1200.5",
			"'12,345',   12345",
			"'1 200',    1200",
	})
	@DisplayName("reads a thousands separator, including the space form PDFs produce")
	void parsesThousandsSeparators(String raw, double expected) {
		assertThat(ValueParser.parse(raw).number()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"'5,4',     5.4",
			"'12,75',   12.75",
	})
	@DisplayName("reads a decimal comma")
	void parsesDecimalComma(String raw, double expected) {
		// A single comma with one or two digits after it cannot be a thousands separator.
		assertThat(ValueParser.parse(raw).number()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} -> {1} {2}")
	@CsvSource({
			"<0.5,     0.5,  LT",
			"'< 0.5',  0.5,  LT",
			"≤0.5,     0.5,  LE",
			"<=0.5,    0.5,  LE",
			">200,     200,  GT",
			"'> 200',  200,  GT",
			"≥200,     200,  GE",
			">=200,    200,  GE",
	})
	@DisplayName("keeps the comparator separate from the number")
	void parsesComparators(String raw, double expected, Comparator comparator) {
		ParsedValue value = ValueParser.parse(raw);

		assertThat(value.number()).isEqualTo(expected);
		assertThat(value.comparator()).isEqualTo(comparator);
	}

	@ParameterizedTest(name = "{0} is not a trend point")
	@ValueSource(strings = { "<0.5", "≤0.5", ">200", "≥200" })
	@DisplayName("a comparator value is never stored as a number")
	void comparatorValuesAreNotStored(String raw) {
		ParsedValue value = ValueParser.parse(raw);

		// "<0.5" is not a measurement of 0.5. Storing it as one would draw a trend line
		// through a number the lab never reported (LLD 11.3).
		assertThat(value.number()).isNotNull();
		assertThat(value.storedNumber()).isNull();
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"'5.4 H',    5.4",
			"5.4*,       5.4",
			"'162 HIGH', 162",
			"'38 L',     38",
			"'7.1 ABN',  7.1",
	})
	@DisplayName("strips a trailing flag marker and keeps the number")
	void stripsTrailingFlagMarkers(String raw, double expected) {
		// The flag itself is ignored: it is recomputed in code from the parsed range.
		assertThat(ValueParser.parse(raw).number()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} is not numeric")
	@ValueSource(strings = {
			// Qualitative results, which render as text rather than on a range bar.
			"Negative", "Positive", "Trace", "Reactive", "Non-reactive", "Not detected",
			"Detected", "Normal", "Clear", "Yellow",
			// Nothing to read.
			"--", "-", "N/A", "n/a", "pending", "QNS",
			// Malformed numbers. Guessing at any of these risks a wrong value.
			"1.2.3", "1,23,456", "1,2345", ".", ",", "5..4",
			// A unit or word attached, so this is not a bare value.
			"5.4 mg/dL", "5 of 10", "99 percent", "12 units",
	})
	@DisplayName("refuses anything it cannot read as a number")
	void refusesNonNumericValues(String raw) {
		ParsedValue value = ValueParser.parse(raw);

		assertThat(value.isNumeric()).isFalse();
		assertThat(value.number()).isNull();
		assertThat(value.storedNumber()).isNull();
		assertThat(value.comparator()).isEqualTo(Comparator.NONE);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t" })
	@DisplayName("handles null, empty and blank without throwing")
	void handlesMissingValues(String raw) {
		assertThat(ValueParser.parse(raw).isNumeric()).isFalse();
	}

	@Test
	@DisplayName("a very long digit string does not become Infinity")
	void refusesOverflow() {
		String huge = "9".repeat(400);

		// Double.parseDouble would return Infinity here, which would then be stored and
		// rendered. BigDecimal plus a finiteness check refuses it instead.
		assertThat(ValueParser.parse(huge).isNumeric()).isFalse();
	}

	@Test
	@DisplayName("a long but representable number still parses")
	void acceptsLargeRepresentableNumbers() {
		assertThat(ValueParser.parse("1234567890").number()).isEqualTo(1234567890d);
	}

	@Test
	@DisplayName("a unicode minus is not mistaken for a negative sign")
	void refusesUnicodeMinus() {
		// U+2212 MINUS SIGN. Accepting it would mean accepting a character the lab did not
		// print as a sign; rejecting it shows the value as text, which is the safe direction.
		assertThat(ValueParser.parse("−2.5").isNumeric()).isFalse();
	}
}
