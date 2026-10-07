package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Collection-date parsing ({@code LLD} 11.6).
 *
 * <p>A wrong date puts a result at the wrong point in a patient's history; no date just leaves
 * the report out of the chart. So the refusal cases here carry more weight than the accepting
 * ones, and the ambiguity case - {@code 03/04/2026} - is the one that matters most.
 *
 * <p>The clock is fixed at 2026-10-07 so the future check is deterministic.
 */
class CollectedDateParserTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

	private final CollectedDateParser parser = new CollectedDateParser(
			Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"2026-09-28,  2026-09-28",
			"2026-9-28,   2026-09-28",
			"2026-01-05,  2026-01-05",
	})
	@DisplayName("reads an ISO date")
	void parsesIsoDates(String printed, String expected) {
		assertThat(parse(printed)).contains(LocalDate.parse(expected));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			// Only one reading is a real date, because 28 is not a month.
			"09/28/2026,  2026-09-28",
			"9/28/2026,   2026-09-28",
			"09-28-2026,  2026-09-28",
			"09.28.2026,  2026-09-28",
			// Only one reading works the other way round, because 15 is not a month.
			"15/09/2026,  2026-09-15",
			"28/09/2026,  2026-09-28",
			"31/01/2026,  2026-01-31",
	})
	@DisplayName("reads a numeric date when only one reading is a real date")
	void parsesUnambiguousNumericDates(String printed, String expected) {
		// Deliberately not resolved by locale: the report does not say which convention wrote
		// it, and a server default would mis-date every report from the other one.
		assertThat(parse(printed)).contains(LocalDate.parse(expected));
	}

	@ParameterizedTest(name = "{0} is refused as ambiguous")
	@ValueSource(strings = {
			"03/04/2026", "01/02/2026", "12/11/2026", "05/06/2026", "1/2/2026",
	})
	@DisplayName("refuses a numeric date that reads two ways")
	void refusesAmbiguousNumericDates(String printed) {
		// Both readings are real dates and nothing in the document decides between them.
		assertThat(parse(printed)).isEmpty();
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"07/07/2026,  2026-07-07",
			"01/01/2026,  2026-01-01",
	})
	@DisplayName("an ambiguous-looking date where both readings agree is accepted")
	void acceptsHarmlessAmbiguity(String printed, String expected) {
		// Day equals month, so there is nothing to get wrong.
		assertThat(parse(printed)).contains(LocalDate.parse(expected));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"'28 Sep 2026',      2026-09-28",
			"'2 Oct 2026',       2026-10-02",
			"'28 September 2026',2026-09-28",
			"'September 28, 2026',2026-09-28",
			"'Sep 28, 2026',     2026-09-28",
			"'Sep. 28, 2026',    2026-09-28",
			"'Oct 2 2026',       2026-10-02",
	})
	@DisplayName("reads a date with a month name, which carries no ambiguity")
	void parsesNamedMonthDates(String printed, String expected) {
		assertThat(parse(printed)).contains(LocalDate.parse(expected));
	}

	@Test
	@DisplayName("refuses a date the masked text does not contain")
	void refusesDateNotInText() {
		// The support check. Without it, a model that normalised the date - or invented one
		// from context - would have its version stored (CLAUDE.md rule 4).
		assertThat(parser.parse("2026-09-28", "Collected: 15/09/2026")).isEmpty();
		assertThat(parser.parse("2026-09-28", "")).isEmpty();
		assertThat(parser.parse("2026-09-28", null)).isEmpty();
	}

	@Test
	@DisplayName("accepts a date that does appear in the text")
	void acceptsDateInText() {
		assertThat(parser.parse("09/28/2026", "Collected: 09/28/2026\nTSH 0.21"))
				.contains(LocalDate.of(2026, 9, 28));
	}

	@ParameterizedTest(name = "{0} is refused as in the future")
	@ValueSource(strings = { "2026-10-08", "2026-12-31", "2027-01-01", "2030-06-15" })
	@DisplayName("refuses a future date")
	void refusesFutureDates(String printed) {
		// A specimen cannot be collected in the future, so this is a misread year.
		assertThat(parse(printed)).isEmpty();
	}

	@Test
	@DisplayName("accepts today")
	void acceptsToday() {
		assertThat(parse("2026-10-07")).contains(TODAY);
	}

	@ParameterizedTest(name = "{0} is refused as too old")
	@ValueSource(strings = { "1989-12-31", "1985-04-12", "1900-01-01" })
	@DisplayName("refuses a date before 1990")
	void refusesAncientDates(String printed) {
		// Catches the same misread-year mistake from the other direction - and in particular a
		// birth date that survived masking and was read as the collection date.
		assertThat(parse(printed)).isEmpty();
	}

	@Test
	@DisplayName("accepts 1990 itself")
	void acceptsEarliestAllowed() {
		assertThat(parse("1990-01-01")).contains(LocalDate.of(1990, 1, 1));
	}

	@ParameterizedTest(name = "{0} is refused")
	@ValueSource(strings = {
			// Not dates.
			"2026-13-01", "2026-02-30", "2026-00-10", "32/01/2026", "00/01/2026",
			// Unsupported or incomplete shapes.
			"Sep 2026", "2026", "09/2026", "28 Septemba 2026", "yesterday",
			"Collected on Tuesday", "--", "N/A",
			// Two-digit years: 26 could be 1926 or 2026.
			"09/28/26", "28/09/26",
	})
	@DisplayName("refuses anything it cannot read as one certain date")
	void refusesUnreadableDates(String printed) {
		assertThat(parse(printed)).isEmpty();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t" })
	@DisplayName("a missing date is empty, which is a normal outcome")
	void handlesMissingDate(String printed) {
		// Plenty of reports print no collection date. That is not an error.
		assertThat(parser.parse(printed, "some text")).isEmpty();
	}

	@Test
	@DisplayName("tolerates surrounding whitespace")
	void tolerantOfWhitespace() {
		assertThat(parser.parse("  09/28/2026  ", "Collected: 09/28/2026"))
				.contains(LocalDate.of(2026, 9, 28));
	}

	/** Uses the printed string as its own supporting text, for the format cases. */
	private java.util.Optional<LocalDate> parse(String printed) {
		return parser.parse(printed, "Collected: " + printed);
	}
}
