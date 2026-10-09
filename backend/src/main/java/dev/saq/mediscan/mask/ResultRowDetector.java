package dev.saq.mediscan.mask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Finds the lines that carry lab results, so masking cannot touch them.
 *
 * <p>This is the safety half of masking. The rules that follow are heuristics over text that
 * also contains digits, and a phone-number pattern will happily match part of a result row
 * given the chance. If that happened the patient would be shown a wrong value, or the
 * extraction step would drop the row - both worse outcomes than leaving a name unmasked. So
 * result rows are identified first and treated as untouchable, and a rule that wants one is
 * recorded as a conflict rather than allowed.
 *
 * <p><strong>The rule.</strong> A line is a result row when it contains a number
 * <em>and</em> at least one of: a unit token, a reference-range pattern, or a trailing flag
 * marker. Requiring a second signal is what keeps {@code Patient ID: 12345} out - it has a
 * number and nothing else, so it stays maskable, which is the whole point of the
 * {@code IdentifierRule}.
 *
 * <p>The whole line is protected, including the test name and the range. A reference range is
 * as load-bearing as the value: the flag is computed from it, and masking half of
 * {@code 3.5-5.0} would silently turn a {@code NORMAL} into an {@code UNKNOWN}.
 */
public final class ResultRowDetector {

	/** Any digit sequence. A line with no number cannot be a result row. */
	private static final Pattern NUMBER = Pattern.compile("\\d");

	/**
	 * Units seen on lab reports.
	 *
	 * <p>Word boundaries are not usable here - {@code mg/dL} and {@code x10^3/uL} contain
	 * slashes and carets - so each is matched as a token delimited by whitespace or line
	 * edges. Over-matching is the safe direction: a false unit protects a line that did not
	 * need protecting, which costs an unmasked name only if a name happens to share that line.
	 */
	private static final List<String> UNITS = List.of(
			"mg/dl", "g/dl", "mg/l", "ug/dl", "µg/dl", "ng/ml", "pg/ml", "ug/ml", "µg/ml",
			"mmol/l", "umol/l", "µmol/l", "nmol/l", "pmol/l", "meq/l", "mosm/kg",
			"iu/l", "u/l", "miu/l", "uiu/ml", "µiu/ml", "iu/ml", "mu/l",
			"x10^3/ul", "x10^3/µl", "x10^6/ul", "x10^6/µl", "10^9/l", "10^12/l",
			"/ul", "/µl", "/hpf", "/lpf", "cells/ul", "cells/µl",
			"fl", "pg", "%", "ratio", "seconds", "sec", "mm/hr", "ml/min",
			"ml/min/1.73m2", "g/l", "mg/24h", "mmhg");

	/**
	 * {@code 3.5-5.0}, {@code 3.5 – 5.0}, {@code 150-400}.
	 *
	 * <p>Deliberately narrow, and this is the most delicate pattern in the class. A naive
	 * "number, hyphen, number" also matches a phone number ({@code 555-0142}), a specimen id
	 * ({@code 20260928-0114}) and an ISO date ({@code 2026-10-03}) - and matching any of
	 * those would mark the header line as a result row, making it <em>unmaskable</em>. That
	 * is a privacy failure, not a cosmetic one, so the pattern is bounded two ways:
	 *
	 * <ul>
	 * <li>at most three integer digits per side, which admits every reference range on a lab
	 * report while excluding the four-digit groups that dates, phone numbers and ids are made
	 * of;</li>
	 * <li>lookarounds rejecting an adjacent digit, dot or hyphen, so the pattern cannot match
	 * the middle of a longer run - {@code 10-03} inside {@code 2026-10-03}.</li>
	 * </ul>
	 */
	private static final Pattern RANGE_PAIR = Pattern.compile(
			"(?<![\\d.\\-–—])\\d{1,3}(?:\\.\\d+)?\\s*[-–—]\\s*\\d{1,3}(?:\\.\\d+)?(?![\\d.\\-–—])");

	/**
	 * {@code 70 to 100}.
	 *
	 * <p>Separate from the hyphen form, and much looser, because the word {@code to} between
	 * two numbers is unambiguous - no date, phone number or identifier is written that way.
	 */
	private static final Pattern RANGE_TO = Pattern.compile(
			"\\b\\d+(?:\\.\\d+)?\\s+to\\s+\\d+(?:\\.\\d+)?\\b",
			Pattern.CASE_INSENSITIVE);

	/** {@code <200}, {@code >= 60}, {@code ≤ 0.5}. */
	private static final Pattern RANGE_BOUND = Pattern.compile("[<>≤≥]\\s*=?\\s*\\d+(?:\\.\\d+)?");

	/**
	 * A flag marker at the end of a line: {@code H}, {@code L}, {@code HIGH}, {@code LOW},
	 * {@code ABNORMAL}, or a lone asterisk.
	 *
	 * <p>Anchored to the line end so a stray capital {@code H} mid-sentence does not count.
	 */
	private static final Pattern TRAILING_FLAG = Pattern.compile(
			"(?:(?:\\s|\\*)(?:h|l|hh|ll|high|low|abn|abnormal|crit|critical)|\\s\\*+)\\s*$",
			Pattern.CASE_INSENSITIVE);

	private ResultRowDetector() {
	}

	/**
	 * The protected spans of {@code text}, one per result row, in order.
	 *
	 * <p>Each span covers a whole line, excluding its newline.
	 */
	public static ProtectedSpans detect(String text) {
		List<Span> rows = new ArrayList<>();

		int lineStart = 0;
		while (lineStart <= text.length()) {
			int newline = text.indexOf('\n', lineStart);
			int lineEnd = newline < 0 ? text.length() : newline;

			if (lineEnd > lineStart && isResultRow(text.substring(lineStart, lineEnd))) {
				// MaskType is irrelevant for a protected span - nothing will replace it - but
				// Span requires one, and NAME is the type most often wrongly matched here.
				rows.add(new Span(lineStart, lineEnd, MaskType.NAME));
			}

			if (newline < 0) {
				break;
			}
			lineStart = newline + 1;
		}

		return new ProtectedSpans(rows);
	}

	/** Whether one line looks like it carries a lab result. */
	static boolean isResultRow(String line) {
		if (!NUMBER.matcher(line).find()) {
			return false;
		}
		return hasUnit(line) || hasRange(line) || TRAILING_FLAG.matcher(line).find();
	}

	private static boolean hasRange(String line) {
		return RANGE_PAIR.matcher(line).find()
				|| RANGE_TO.matcher(line).find()
				|| RANGE_BOUND.matcher(line).find();
	}

	/**
	 * Whether the line contains a unit as a standalone token.
	 *
	 * <p>Token-delimited rather than a substring search, so {@code Fluid} does not count as
	 * {@code fl} and {@code Upgrade} does not count as {@code pg}.
	 */
	private static boolean hasUnit(String line) {
		String lower = line.toLowerCase(Locale.ROOT);

		for (String unit : UNITS) {
			int from = 0;
			int found;
			while ((found = lower.indexOf(unit, from)) >= 0) {
				if (isTokenBoundary(lower, found - 1) && isTokenBoundary(lower, found + unit.length())) {
					return true;
				}
				from = found + 1;
			}
		}
		return false;
	}

	/**
	 * Whether the character at {@code index} ends a token.
	 *
	 * <p>Off the end of the string counts. Letters and digits do not, so a unit has to stand
	 * on its own; punctuation does, so {@code (mg/dL)} and {@code 99mg/dL} both match.
	 */
	private static boolean isTokenBoundary(String text, int index) {
		if (index < 0 || index >= text.length()) {
			return true;
		}
		char character = text.charAt(index);
		return !Character.isLetter(character);
	}
}
