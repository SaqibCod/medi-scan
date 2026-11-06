package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.saq.mediscan.analysis.ParsedValue;
import dev.saq.mediscan.analysis.ValueParser;

/**
 * Building the set of numbers an answer may contain ({@code LLD} 7).
 *
 * <p>Includes a cross-check against {@link ValueParser}. {@code chat} does not call
 * {@code analysis} - the dependency rule forbids it - so this class normalises numbers itself,
 * and a copied rule is a rule that can drift. The check below is what keeps the copy honest:
 * if the pipeline ever changes how it reads "1,234", this fails rather than the guard quietly
 * refusing a value that is printed on the report.
 */
class AllowedNumbersTest {

	private static final AllowedNumbers REPORT = AllowedNumbers.of(
			List.of("238", "<200", "1,234", "5,4"),
			"Collected 2026-09-28. Total 238 mg/dL.",
			List.of(5, 2),
			LocalDate.of(2026, 9, 28));

	@ParameterizedTest
	@ValueSource(doubles = {238, 200, 1234, 5.4, 5, 2, 2026, 28, 9})
	@DisplayName("numbers from the report, counts and the date are allowed")
	void reportNumbersAreAllowed(double number) {
		assertThat(REPORT.isAllowed(number)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(doubles = {130, 199, 70, 240, 35.5})
	@DisplayName("numbers that are not on the report are refused")
	void otherNumbersAreRefused(double number) {
		assertThat(REPORT.isAllowed(number)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3, 10})
	@DisplayName("small counting numbers are always allowed")
	void countingNumbersAreAllowed(int number) {
		// List markers and "both of them". A clinical cut-off is never this small, so the cost
		// of allowing them is nil and the cost of refusing them is every list being stopped.
		assertThat(REPORT.isAllowed(number)).isTrue();
	}

	@Test
	@DisplayName("11 is not a counting number")
	void countingStopsAtTen() {
		assertThat(AllowedNumbers.of(List.of(), "", List.of(), null).isAllowed(11)).isFalse();
	}

	@ParameterizedTest
	@CsvSource({
			"162, 162",
			"162.0, 162",
			"1234, 1234",
			"'1,234', 1234",
			"'5,4', 5.4",
			"0.5, 0.5",
			".5, 0.5",
	})
	@DisplayName("tokens are normalised so equivalent spellings match")
	void normalisesEquivalentSpellings(String token, double expected) {
		assertThat(AllowedNumbers.parse(token)).isEqualTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = {"1.2.3", "1,23,456", ",", "."})
	@DisplayName("a token that cannot be read as one number is rejected")
	void unreadableTokensAreRejected(String token) {
		// Not guessed at. "1,23,456" has no single reading, and inventing one would either
		// allow a number the report never printed or refuse one it did.
		assertThat(AllowedNumbers.parse(token)).isNull();
	}

	@ParameterizedTest
	@CsvSource({"'162.', 162", "'162,', 162"})
	@DisplayName("trailing sentence punctuation is not part of the number")
	void trailingPunctuationIsStripped(String token, double expected) {
		assertThat(AllowedNumbers.parse(token)).isEqualTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = {"162", "1,234", "5,4", "238", "0.5"})
	@DisplayName("this agrees with ValueParser on how a printed value reads")
	void agreesWithValueParser(String printed) {
		// The cross-check. chat cannot call analysis, so the normalising rules are duplicated;
		// this is what stops the duplicate drifting. A disagreement here means the guard would
		// refuse a number that is printed on the reader's own report.
		ParsedValue pipeline = ValueParser.parse(printed);

		assertThat(pipeline.isNumeric())
				.as("ValueParser should read %s as a number", printed)
				.isTrue();
		assertThat(AllowedNumbers.parse(printed))
				.as("the guard and the pipeline must agree on %s", printed)
				.isEqualTo(pipeline.number());
	}

	@Test
	@DisplayName("a reference range contributes both of its bounds")
	void rangesContributeBothBounds() {
		AllowedNumbers allowed =
				AllowedNumbers.of(List.of("5-40"), "", List.of(), null);

		assertThat(allowed.isAllowed(5)).isTrue();
		assertThat(allowed.isAllowed(40)).isTrue();
	}

	@Test
	@DisplayName("numbers in the masked report text are allowed")
	void maskedTextContributes() {
		// An answer may quote a figure printed in the report body rather than in a result row.
		AllowedNumbers allowed = AllowedNumbers.of(
				List.of(), "Fasting for 12 hours before the sample.", List.of(), null);

		assertThat(allowed.isAllowed(12)).isTrue();
	}

	@Test
	@DisplayName("disallowedIn reports every offending number in a sentence")
	void reportsEveryOffender() {
		assertThat(REPORT.disallowedIn("Normal is under 130 and high is over 240."))
				.containsExactly(130.0, 240.0);
	}

	@Test
	@DisplayName("a sentence with no numbers has nothing to refuse")
	void noNumbersNoOffenders() {
		assertThat(REPORT.disallowedIn("Your cholesterol is higher than the printed range."))
				.isEmpty();
	}
}
