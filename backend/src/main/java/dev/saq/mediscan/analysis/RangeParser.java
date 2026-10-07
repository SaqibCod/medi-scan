package dev.saq.mediscan.analysis;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the printed reference range into bounds.
 *
 * <p>Parsed in code, never taken from the model ({@code CLAUDE.md} rule 4). The flag shown to
 * the patient is computed from these bounds, so a misread range produces a wrong flag - which
 * is worse than no flag at all. Everything this parser is not certain about therefore returns
 * empty, and the flag becomes {@code UNKNOWN} with the printed range still displayed for the
 * reader to interpret.
 *
 * <p>The two cases it most deliberately refuses are ranges that depend on something the report
 * does not tell us: {@code M: 13.5-17.5 F: 12-16} depends on sex, {@code Adult 10-20 / Child
 * 5-15} on age. Picking one would be a clinical judgement made by a regex.
 *
 * <p>The table of accepted forms is in {@code LLD} 11.4 and mirrored in
 * {@code RangeParserTest}.
 */
public final class RangeParser {

	/** {@code 3.5-5.0}, {@code 3.5 – 5.0}, {@code 3.5 to 5.0}. */
	private static final Pattern PAIR = Pattern.compile(
			"^\\s*(?<low>-?\\d+(?:\\.\\d+)?)\\s*(?:[-–—]|to)\\s*(?<high>-?\\d+(?:\\.\\d+)?)\\s*$",
			Pattern.CASE_INSENSITIVE);

	/**
	 * A negative lower bound is only readable with the word {@code to}.
	 *
	 * <p>{@code -2 - 2} is genuinely ambiguous about where the range separator is, so it is
	 * refused rather than guessed ({@code LLD} 11.4).
	 */
	private static final Pattern NEGATIVE_PAIR_WITH_TO = Pattern.compile(
			"^\\s*(?<low>-\\d+(?:\\.\\d+)?)\\s+to\\s+(?<high>-?\\d+(?:\\.\\d+)?)\\s*$",
			Pattern.CASE_INSENSITIVE);

	/** {@code <200}, {@code <= 200}, {@code ≤200}. */
	private static final Pattern UPPER_BOUND = Pattern.compile(
			"^\\s*(?<comparator><=?|≤)\\s*(?<high>-?\\d+(?:\\.\\d+)?)\\s*$");

	/** {@code >60}, {@code >= 60}, {@code ≥60}. */
	private static final Pattern LOWER_BOUND = Pattern.compile(
			"^\\s*(?<comparator>>=?|≥)\\s*(?<low>-?\\d+(?:\\.\\d+)?)\\s*$");

	/** Any number, for the "too many numbers to be one range" check. */
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

	/** A trailing unit, which labs often append to the range. */
	private static final Pattern TRAILING_UNIT = Pattern.compile(
			"\\s*(?:mg/dL|g/dL|mmol/L|umol/L|µmol/L|mEq/L|IU/L|U/L|ng/mL|pg/mL|uIU/mL|µIU/mL"
					+ "|x10\\^[36]/u?µ?L|10\\^9/L|/uL|/µL|/HPF|fL|pg|%|mL/min|mm/hr|seconds|sec)"
					+ "\\s*$",
			Pattern.CASE_INSENSITIVE);

	private RangeParser() {
	}

	/** Parses {@code referenceRangeText}; empty when it cannot be read with certainty. */
	public static Optional<Range> parse(String referenceRangeText) {
		if (referenceRangeText == null || referenceRangeText.isBlank()) {
			return Optional.empty();
		}

		String text = TRAILING_UNIT.matcher(referenceRangeText.strip()).replaceAll("").strip();
		if (text.isEmpty()) {
			return Optional.empty();
		}

		// Three or more numbers is not one range. It is a sex-dependent or age-dependent
		// range, or a range with a unit this parser did not strip - all cases where choosing
		// a pair would be a guess (LLD 11.4).
		if (countNumbers(text) > 2) {
			return Optional.empty();
		}

		Optional<Range> negativePair = parseNegativePair(text);
		if (negativePair.isPresent()) {
			return negativePair;
		}

		Matcher pair = PAIR.matcher(text);
		if (pair.matches()) {
			// A leading minus here is the separator's doing, not a sign: "-2-2" cannot be
			// read reliably, so only the `to` form above accepts a negative low bound.
			if (text.stripLeading().startsWith("-")) {
				return Optional.empty();
			}
			return validPair(Double.parseDouble(pair.group("low")),
					Double.parseDouble(pair.group("high")));
		}

		Matcher upper = UPPER_BOUND.matcher(text);
		if (upper.matches()) {
			boolean inclusive = !"<".equals(upper.group("comparator"));
			return Optional.of(Range.upTo(Double.parseDouble(upper.group("high")), inclusive));
		}

		Matcher lower = LOWER_BOUND.matcher(text);
		if (lower.matches()) {
			boolean inclusive = !">".equals(lower.group("comparator"));
			return Optional.of(Range.from(Double.parseDouble(lower.group("low")), inclusive));
		}

		// "Negative", "Not detected", "See comment", or anything else qualitative.
		return Optional.empty();
	}

	private static Optional<Range> parseNegativePair(String text) {
		Matcher matcher = NEGATIVE_PAIR_WITH_TO.matcher(text);
		if (!matcher.matches()) {
			return Optional.empty();
		}
		return validPair(Double.parseDouble(matcher.group("low")),
				Double.parseDouble(matcher.group("high")));
	}

	/**
	 * Accepts a pair only if it describes a real interval.
	 *
	 * <p>{@code 5 - 3} is reversed and {@code 0.0 - 0.0} is empty. Both appear on real reports
	 * as placeholders for "no range established", and treating either as a range would flag
	 * every value as out of range.
	 */
	private static Optional<Range> validPair(double low, double high) {
		if (low >= high) {
			return Optional.empty();
		}
		return Optional.of(Range.between(low, high));
	}

	private static int countNumbers(String text) {
		Matcher matcher = NUMBER.matcher(text);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}
}
