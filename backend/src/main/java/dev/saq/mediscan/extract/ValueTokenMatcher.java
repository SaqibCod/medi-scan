package dev.saq.mediscan.extract;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a printed value inside report text, as a whole token.
 *
 * <p>One definition, used by two callers that must agree. {@code BiomarkerValidator}'s support
 * check asks <em>whether</em> a {@code rawValue} appears in the masked text, and
 * {@link ConfidenceIndex} asks <em>where</em> it appears so it can read the OCR confidence of
 * those characters. If the two disagreed about what counts as an occurrence, a value could pass
 * the support check and then have its confidence read from the wrong characters - or from none,
 * silently defeating the low-confidence rule. So the rule lives here and both call it.
 *
 * <p><strong>Whole-token matching.</strong> A substring search would accept {@code 5.4} because
 * the text contains {@code 15.42}, which is exactly the near-miss a model produces when it
 * misreads a column. The boundaries reject an adjacent digit or decimal point while still
 * allowing the value to sit beside a space, a bracket or a unit.
 *
 * <p><strong>Whitespace.</strong> The value is collapsed to single spaces and the text is
 * searched with runs of whitespace allowed wherever the value had a space, because PDF
 * extraction turns {@code 1,200} into {@code 1 200} unpredictably and OCR splits on wide gaps.
 *
 * <p>Lives in {@code extract} so {@code analysis} can use it: the dependency runs that way and
 * not the other ({@code backend/CLAUDE.md}, "Dependency direction").
 */
public final class ValueTokenMatcher {

	private ValueTokenMatcher() {
	}

	/**
	 * Whether {@code value} appears in {@code text} as a whole token.
	 *
	 * <p>This is the support check: a {@code rawValue} that fails it was invented or altered by
	 * the model, and its row is dropped.
	 */
	public static boolean appearsIn(String value, String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		Pattern pattern = patternFor(value);
		return pattern != null && pattern.matcher(collapse(text).text()).find();
	}

	/**
	 * The pattern that matches {@code value} as a whole token, or null if there is nothing to
	 * match.
	 *
	 * <p>Match it against {@link #collapse(String)}'s output, never against raw text: the
	 * pattern assumes single spaces.
	 */
	static Pattern patternFor(String value) {
		if (value == null) {
			return null;
		}
		String needle = value.strip().replaceAll("\\s+", " ");
		if (needle.isEmpty()) {
			return null;
		}
		// Quote the whole value, then re-open the quoting around each space so a run of
		// whitespace in the text still matches.
		String escaped = Pattern.quote(needle).replace(" ", "\\E\\s+\\Q");
		return Pattern.compile("(?<![\\w.])" + escaped + "(?![\\w.])");
	}

	/**
	 * Collapses runs of spaces and tabs to one space, remembering where each kept character
	 * came from.
	 *
	 * <p>The offsets are the point. A match found in the collapsed text has to be translated
	 * back to the original to read the confidence of the right OCR words, and a lab report's
	 * column gaps mean the two differ by a lot.
	 *
	 * <p>Only spaces and tabs, deliberately - not newlines. A value never spans a line, and
	 * collapsing newlines would let a match run across two unrelated rows.
	 */
	static Collapsed collapse(String text) {
		StringBuilder collapsed = new StringBuilder(text.length());
		int[] origin = new int[text.length()];
		int kept = 0;
		int index = 0;

		while (index < text.length()) {
			char character = text.charAt(index);
			if (character == ' ' || character == '\t') {
				collapsed.append(' ');
				origin[kept++] = index;
				while (index < text.length()
						&& (text.charAt(index) == ' ' || text.charAt(index) == '\t')) {
					index++;
				}
			}
			else {
				collapsed.append(character);
				origin[kept++] = index;
				index++;
			}
		}

		return new Collapsed(collapsed.toString(), java.util.Arrays.copyOf(origin, kept),
				text.length());
	}

	/**
	 * Text with its whitespace collapsed, and the map back to where it came from.
	 *
	 * @param text the collapsed text, to match against
	 * @param origin for each character of {@link #text}, its index in the original
	 * @param originalLength the original's length, for translating an end offset
	 */
	record Collapsed(String text, int[] origin, int originalLength) {

		/** Where a match found at collapsed offset {@code at} starts in the original. */
		int originalStart(int at) {
			return at < origin.length ? origin[at] : originalLength;
		}

		/** Where a match ending at collapsed offset {@code end} ends in the original. */
		int originalEnd(int end) {
			if (end <= 0) {
				return 0;
			}
			return end - 1 < origin.length ? origin[end - 1] + 1 : originalLength;
		}

		/** Hides the text: this is report content. */
		@Override
		public String toString() {
			return "Collapsed[chars=" + text.length() + ", originalLength=" + originalLength + "]";
		}
	}

	/** Every occurrence of {@code value} in {@code text}, as original-text offsets. */
	static java.util.List<int[]> occurrences(String value, String text) {
		Pattern pattern = patternFor(value);
		if (pattern == null || text == null || text.isEmpty()) {
			return java.util.List.of();
		}

		Collapsed collapsed = collapse(text);
		java.util.List<int[]> found = new java.util.ArrayList<>();
		Matcher matcher = pattern.matcher(collapsed.text());
		while (matcher.find()) {
			found.add(new int[] {
					collapsed.originalStart(matcher.start()),
					collapsed.originalEnd(matcher.end()) });
		}
		return found;
	}
}
