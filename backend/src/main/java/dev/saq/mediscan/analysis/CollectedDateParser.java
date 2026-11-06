package dev.saq.mediscan.analysis;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Reads the specimen collection date, or returns nothing.
 *
 * <p>The date drives the trend charts, which means a wrong one puts a result at the wrong point
 * in a patient's history - a worse outcome than having no date, which simply leaves the report
 * out of the chart. So this parser refuses every ambiguity rather than resolving it
 * ({@code backend/CLAUDE.md}: "when a value or date is ambiguous, don't guess").
 *
 * <p>The ambiguity that matters is {@code 03/04/2026}: valid as both 3 April and 4 March, with
 * nothing in the document to say which. The rule is to accept a numeric date only when exactly
 * one of the two readings is a real date - {@code 09/28/2026} can only be September 28th, and
 * {@code 15/09/2026} can only be the 15th of September.
 *
 * <p>Three further checks, all from {@code LLD} 11.6: the string must appear in the masked text
 * (so the model cannot invent a date), it must not be in the future, and it must not predate
 * 1990. The last two catch a misread year, which is the most common way a date goes wrong.
 */
@Component
public class CollectedDateParser {

	/** Nothing before this is a plausible collection date for a report being read today. */
	private static final LocalDate EARLIEST = LocalDate.of(1990, 1, 1);

	private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");

	/** {@code dd/MM/yyyy} or {@code MM/dd/yyyy}, with any of three separators. */
	private static final Pattern NUMERIC = Pattern.compile(
			"^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})$");

	/** Formats with a month name, which carry no ambiguity. */
	private static final List<DateTimeFormatter> NAMED_MONTH_FORMATS = List.of(
			DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
			DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
			DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH),
			DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH));

	private final Clock clock;

	public CollectedDateParser(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Parses the model's {@code collectedOn}, checking it against the text it came from.
	 *
	 * @param printed the date string the model transcribed, which may be {@code null}
	 * @param maskedText the text the model was given, for the support check
	 * @return the date, or empty whenever it cannot be established beyond doubt. Empty is a
	 *     normal outcome, not an error - plenty of reports print no collection date
	 */
	public Optional<LocalDate> parse(String printed, String maskedText) {
		if (printed == null || printed.isBlank()) {
			return Optional.empty();
		}
		String candidate = printed.strip();

		// The model must have read this off the report, not produced it from context. Without
		// this check a model that "helpfully" normalised a date, or invented one, would have
		// its version stored (CLAUDE.md rule 4).
		if (maskedText == null || !maskedText.contains(candidate)) {
			return Optional.empty();
		}

		return interpret(candidate).filter(this::isPlausible);
	}

	private Optional<LocalDate> interpret(String candidate) {
		Matcher iso = ISO.matcher(candidate);
		if (iso.matches()) {
			// Unambiguous by definition.
			return toDate(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
					Integer.parseInt(iso.group(3)));
		}

		Matcher numeric = NUMERIC.matcher(candidate);
		if (numeric.matches()) {
			return interpretNumeric(Integer.parseInt(numeric.group(1)),
					Integer.parseInt(numeric.group(2)), Integer.parseInt(numeric.group(3)));
		}

		return interpretNamedMonth(candidate);
	}

	/**
	 * Resolves {@code a/b/yyyy} only when one reading is a date and the other is not.
	 *
	 * <p>Both readings valid means genuine ambiguity - {@code 03/04/2026} - and the answer is
	 * nothing. Deliberately not resolved by locale: the report does not say which locale wrote
	 * it, and a server-side default would silently mis-date every report from the other
	 * convention.
	 */
	private Optional<LocalDate> interpretNumeric(int first, int second, int year) {
		Optional<LocalDate> dayFirst = toDate(year, second, first);
		Optional<LocalDate> monthFirst = toDate(year, first, second);

		if (dayFirst.isPresent() && monthFirst.isPresent()) {
			// Identical only when first == second, in which case the ambiguity is harmless.
			return dayFirst.equals(monthFirst) ? dayFirst : Optional.empty();
		}
		return dayFirst.isPresent() ? dayFirst : monthFirst;
	}

	private Optional<LocalDate> interpretNamedMonth(String candidate) {
		// Normalise the punctuation labs vary on: "Sep. 28, 2026" and "28 Sep 2026".
		String normalized = candidate.replace(",", " ").replace(".", " ")
				.replaceAll("\\s+", " ").strip();

		for (DateTimeFormatter format : NAMED_MONTH_FORMATS) {
			try {
				return Optional.of(LocalDate.parse(normalized, format));
			}
			catch (DateTimeParseException ex) {
				// Try the next shape.
			}
		}
		return Optional.empty();
	}

	private static Optional<LocalDate> toDate(int year, int month, int day) {
		try {
			return Optional.of(LocalDate.of(year, month, day));
		}
		catch (DateTimeException ex) {
			// 31 February, month 13, and so on. Not a date, which is exactly the signal the
			// ambiguity check relies on.
			return Optional.empty();
		}
	}

	/**
	 * Rejects a date that cannot be a collection date.
	 *
	 * <p>A specimen cannot be collected in the future, and a report old enough to predate 1990
	 * is a misread year rather than a historical record. Both catch the same underlying
	 * mistake from opposite directions.
	 */
	private boolean isPlausible(LocalDate date) {
		LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		return !date.isAfter(today) && !date.isBefore(EARLIEST);
	}
}
