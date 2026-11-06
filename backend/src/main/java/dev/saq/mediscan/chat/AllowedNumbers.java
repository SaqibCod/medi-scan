package dev.saq.mediscan.chat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every number an answer about this report is allowed to contain ({@code LLD} 7).
 *
 * <p>The set is built from the report itself - the values as printed, the reference ranges as
 * printed, the numbers in the masked text, the flag counts and the collection date - plus small
 * integers a person uses to count ("the first one", "both of them", a numbered list). Anything
 * else in an answer came from the model's memory rather than from the reader's paperwork, and
 * {@link AnswerGuard} stops the answer there.
 *
 * <p><strong>Why this is the rule that matters.</strong> A model that says "normal is under
 * 130" has invented a clinical cut-off, and a reader cannot tell that apart from a number
 * copied off their own report. Reference ranges differ between laboratories, which is why the
 * curated pages deliberately state none - so retrieved text never widens this set either.
 *
 * <p><strong>Normalising is self-consistent, not borrowed.</strong> Both sides of the
 * comparison - the allowed set and the sentence being checked - are parsed by
 * {@link #parse(String)} here, so "1,234" on the report matches "1234" in an answer because
 * both become the same {@code double}. The rules mirror
 * {@link dev.saq.mediscan.analysis.ValueParser}'s (thousands separators, decimal commas), and
 * a test checks the two agree, but this class does not depend on that package - {@code chat}
 * does not call {@code analysis}.
 *
 * <p>Immutable once built. One instance per answer.
 */
public final class AllowedNumbers {

	/**
	 * A number as it might appear in prose.
	 *
	 * <p>Deliberately greedy about separators so "1,234.5" is one token rather than three, and
	 * about a leading decimal point so ".5" is caught - the point of extraction here is to find
	 * <em>everything</em> that could be read as a number, because anything missed is a number
	 * the guard never checks.
	 */
	private static final Pattern NUMBER = Pattern.compile("\\d[\\d,.]*|\\.\\d+");

	/**
	 * Small integers a person uses to count rather than to quote a measurement.
	 *
	 * <p>"Both of your 2 flagged results", "1." at the start of a list item, "the first 3
	 * things to ask". Without these the guard would stop almost every list, and a list marker
	 * is not a clinical claim. Capped low on purpose: 130 is not a counting word.
	 */
	private static final int COUNTING_LIMIT = 10;

	/**
	 * How close two numbers must be to count as the same.
	 *
	 * <p>Doubles do not compare exactly, and the two sides here were parsed from different
	 * strings - "5.40" on the report and "5.4" in the answer have to match. Small enough that
	 * two genuinely different lab values can never collide.
	 */
	private static final double TOLERANCE = 1e-9;

	private final Set<Double> allowed;

	private AllowedNumbers(Set<Double> allowed) {
		this.allowed = Set.copyOf(allowed);
	}

	/**
	 * Builds the set for one report.
	 *
	 * @param printedValues every {@code rawValue} and {@code referenceRangeText} on the report,
	 *     as printed
	 * @param maskedText the report's masked text, so a number the answer quotes from the page
	 *     body is allowed even when it is not one of the extracted values
	 * @param counts the flag counts, which an answer legitimately totals up
	 * @param collectedOn the collection date, or null
	 */
	public static AllowedNumbers of(Collection<String> printedValues, String maskedText,
			Collection<Integer> counts, LocalDate collectedOn) {

		Set<Double> allowed = new HashSet<>();

		for (int i = 0; i <= COUNTING_LIMIT; i++) {
			allowed.add((double) i);
		}

		printedValues.forEach(value -> addNumbersIn(allowed, value));
		addNumbersIn(allowed, maskedText);
		counts.forEach(count -> allowed.add((double) count));

		if (collectedOn != null) {
			// The year and the day of month, which is how a date is spoken: "collected on the
			// 28th", "your September 2026 results". The month is a word in every rendering the
			// answer would use.
			allowed.add((double) collectedOn.getYear());
			allowed.add((double) collectedOn.getDayOfMonth());
			allowed.add((double) collectedOn.getMonthValue());
		}

		return new AllowedNumbers(allowed);
	}

	/**
	 * Every number in {@code text} that is not allowed.
	 *
	 * <p>Returns the offending numbers rather than a boolean so the caller can count them. They
	 * are never logged - {@link AnswerGuard} logs only how often it stopped an answer, because
	 * the number itself is part of what the model wrote about someone's report.
	 */
	public List<Double> disallowedIn(String text) {
		List<Double> offenders = new java.util.ArrayList<>();
		Matcher matcher = NUMBER.matcher(text);

		while (matcher.find()) {
			Double number = parse(matcher.group());
			// An unparseable token - "1.2.3", a bare separator - is not a number anyone can
			// read as a measurement, so it is not a claim the guard needs to stop.
			if (number != null && !isAllowed(number)) {
				offenders.add(number);
			}
		}
		return List.copyOf(offenders);
	}

	/** Whether one number may appear in an answer about this report. */
	public boolean isAllowed(double number) {
		for (double candidate : allowed) {
			if (Math.abs(candidate - number) < TOLERANCE) {
				return true;
			}
		}
		return false;
	}

	/** How many distinct numbers this report permits. Logged as a count, never listed. */
	public int size() {
		return allowed.size();
	}

	private static void addNumbersIn(Set<Double> target, String text) {
		if (text == null) {
			return;
		}
		Matcher matcher = NUMBER.matcher(text);
		while (matcher.find()) {
			Double number = parse(matcher.group());
			if (number != null) {
				target.add(number);
			}
		}
	}

	/**
	 * One token as a number, or null when it cannot be read as one.
	 *
	 * <p>Handles the three arrangements that appear on real reports: plain ({@code 162}),
	 * thousands-separated ({@code 1,234}) and decimal-comma ({@code 5,4}, which much of the
	 * world prints). A token with commas in any other arrangement is rejected rather than
	 * guessed at - "1,23,456" has no single reading, and guessing one would either allow a
	 * number the report never printed or reject one it did.
	 */
	static Double parse(String token) {
		String candidate = token.strip();
		if (candidate.isEmpty()) {
			return null;
		}
		// Trailing punctuation: "162." at the end of a sentence, "40%," in a list.
		while (!candidate.isEmpty() && isTrailingPunctuation(candidate)) {
			candidate = candidate.substring(0, candidate.length() - 1);
		}
		if (candidate.isEmpty()) {
			return null;
		}

		String normalized;
		if (candidate.matches("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?")) {
			normalized = candidate.replace(",", "");
		}
		else if (candidate.matches("\\d+,\\d{1,2}")) {
			normalized = candidate.replace(',', '.');
		}
		else if (candidate.indexOf(',') >= 0) {
			return null;
		}
		else {
			normalized = candidate;
		}

		if (!normalized.matches("\\d*\\.?\\d+")) {
			return null;
		}

		try {
			double parsed = new BigDecimal(normalized).doubleValue();
			return Double.isFinite(parsed) ? parsed : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/** A trailing {@code .} or {@code ,} is punctuation, not part of the number. */
	private static boolean isTrailingPunctuation(String candidate) {
		char last = candidate.charAt(candidate.length() - 1);
		return last == '.' || last == ',';
	}
}
