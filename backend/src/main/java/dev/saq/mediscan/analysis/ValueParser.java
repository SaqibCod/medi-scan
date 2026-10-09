package dev.saq.mediscan.analysis;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the value string the model transcribed into a number and a comparator.
 *
 * <p>Parsed in code, never taken from the model ({@code CLAUDE.md} rule 4). The model's job is
 * transcription; this is where the digits become a number, and it is deliberately strict:
 * anything it does not positively recognise becomes "not numeric", which shows as text and
 * flags {@code UNKNOWN}. Showing a patient no number is a disclosed limitation. Showing them
 * the wrong number is not.
 *
 * <p>The table of accepted forms is in {@code LLD} 11.3 and mirrored exactly in
 * {@code ValueParserTest}.
 */
public final class ValueParser {

	/** An optional comparator, then the digits, then an optional trailing flag marker. */
	private static final Pattern VALUE = Pattern.compile(
			"^\\s*(?<comparator>[<>]=?|≤|≥)?\\s*"
					+ "(?<number>[+-]?[\\d][\\d.,\\s]*?)"
					+ "\\s*(?<trailing>\\*+|[A-Za-z]{1,8})?\\s*$");

	/** {@code 1,200} and {@code 1,200.50}: groups of exactly three after the first comma. */
	private static final Pattern THOUSANDS = Pattern.compile("^\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?$");

	/** {@code 5,4}: a single comma with one or two digits after it is a decimal comma. */
	private static final Pattern DECIMAL_COMMA = Pattern.compile("^\\d+,\\d{1,2}$");

	/** A plain number, possibly signed, with at most one decimal point. */
	private static final Pattern PLAIN_NUMBER = Pattern.compile("^[+-]?\\d+(?:\\.\\d+)?$");

	/**
	 * Flag markers that may follow a value. Anything else trailing means the string is not a
	 * value at all, so it is rejected rather than silently trimmed.
	 */
	private static final Pattern FLAG_MARKER = Pattern.compile(
			"^(?:\\*+|h|l|hh|ll|high|low|abn|abnormal|crit|critical|a)$",
			Pattern.CASE_INSENSITIVE);

	private ValueParser() {
	}

	/** Parses {@code rawValue}; never throws. */
	public static ParsedValue parse(String rawValue) {
		if (rawValue == null) {
			return ParsedValue.notNumeric();
		}

		Matcher matcher = VALUE.matcher(rawValue);
		if (!matcher.matches()) {
			// Covers "Negative", "Trace", "--", "" and anything else without a leading number.
			return ParsedValue.notNumeric();
		}

		String trailing = matcher.group("trailing");
		if (trailing != null && !FLAG_MARKER.matcher(trailing).matches()) {
			// A unit or a word stuck to the value. Not a flag, so this is not a bare value and
			// guessing at it risks reading "5 of 10" as 5.
			return ParsedValue.notNumeric();
		}

		Double number = toNumber(matcher.group("number"));
		if (number == null) {
			return ParsedValue.notNumeric();
		}

		return ParsedValue.of(number, comparatorOf(matcher.group("comparator")));
	}

	private static Comparator comparatorOf(String printed) {
		if (printed == null) {
			return Comparator.NONE;
		}
		return switch (printed) {
			case "<" -> Comparator.LT;
			case "<=", "≤" -> Comparator.LE;
			case ">" -> Comparator.GT;
			case ">=", "≥" -> Comparator.GE;
			default -> Comparator.NONE;
		};
	}

	/**
	 * Interprets the digits, resolving the comma ambiguity.
	 *
	 * <p>A comma is a thousands separator in {@code 1,200} and a decimal point in {@code 5,4},
	 * and the only way to tell is the shape of the groups. Anything matching neither shape is
	 * rejected: a value is the one thing in this pipeline that must not be guessed at.
	 *
	 * <p>{@link BigDecimal} rather than {@link Double#parseDouble}, so a long digit string
	 * cannot silently become {@code Infinity}.
	 */
	private static Double toNumber(String digits) {
		// Internal spaces, which PDF extraction produces in "1 200".
		String candidate = digits.replaceAll("\\s", "");
		if (candidate.isEmpty()) {
			return null;
		}

		String sign = "";
		if (candidate.startsWith("+") || candidate.startsWith("-")) {
			sign = candidate.startsWith("-") ? "-" : "";
			candidate = candidate.substring(1);
		}

		String normalized;
		if (THOUSANDS.matcher(candidate).matches()) {
			normalized = candidate.replace(",", "");
		}
		else if (DECIMAL_COMMA.matcher(candidate).matches()) {
			normalized = candidate.replace(',', '.');
		}
		else if (candidate.indexOf(',') >= 0) {
			// A comma in some other arrangement: "1,23,456" or "1,2345". Unreadable.
			return null;
		}
		else {
			normalized = candidate;
		}

		if (!PLAIN_NUMBER.matcher(normalized).matches()) {
			// Rejects "1.2.3" and bare separators.
			return null;
		}

		try {
			double parsed = new BigDecimal(sign + normalized).doubleValue();
			return Double.isFinite(parsed) ? parsed : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}
}
