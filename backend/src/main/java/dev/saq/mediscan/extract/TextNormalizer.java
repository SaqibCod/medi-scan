package dev.saq.mediscan.extract;

import java.util.regex.Pattern;

/**
 * Cleans up extracted text before anything else looks at it.
 *
 * <p>Runs on every path, PDF and pasted alike, so the masking rules and the extraction prompt
 * see one consistent shape. The rules are conservative by design: this stage must not change
 * any lab value, unit or reference range, because masking's integrity check compares the
 * numbers on result rows before and after - and because a mangled value is a wrong result
 * shown to a patient.
 *
 * <p>Specifically it does <em>not</em> collapse runs of spaces. Lab reports are column
 * layouts, and the gap between a test name and its value is the only thing separating them.
 */
final class TextNormalizer {

	/** Three or more newlines, possibly with whitespace between them. */
	private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("(?:[ \\t]*\\n){3,}");

	/** Control characters, except tab and newline, which are handled separately. */
	private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}&&[^\\t\\n]]");

	/** Trailing whitespace on a line, which column layouts leave behind in quantity. */
	private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+\\n");

	private TextNormalizer() {
	}

	static String normalize(String raw) {
		String text = raw
				// CRLF and lone CR both become \n, so line-based rules see one form.
				.replace("\r\n", "\n")
				.replace('\r', '\n');

		// Tabs become a single space: PDF extraction emits them unpredictably, and a tab in
		// the middle of a result row makes column detection worse rather than better.
		text = text.replace('\t', ' ');

		// After the tab replacement, so a stray control character cannot survive as one.
		text = CONTROL_CHARACTERS.matcher(text).replaceAll("");

		text = TRAILING_SPACE.matcher(text).replaceAll("\n");
		text = EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n");

		return text.strip();
	}
}
