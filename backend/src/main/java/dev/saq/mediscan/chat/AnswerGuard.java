package dev.saq.mediscan.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds every sentence until it passes the number check ({@code LLD} 7, {@code CLAUDE.md}
 * rule 18).
 *
 * <p>This is why answers are not forwarded token by token. A number cannot be unsent: once
 * "normal is under 130" has reached the browser, no later check can take it back. So tokens
 * are buffered into whole sentences, each sentence is checked against
 * {@link AllowedNumbers}, and only then does it become {@code token} events. The cost is a
 * short delay before each sentence appears instead of a per-word trickle; the benefit is that
 * "never invent a number" is enforceable rather than hoped for.
 *
 * <p><strong>A failure stops the answer rather than editing it.</strong> Dropping the offending
 * sentence and carrying on would leave an answer whose reasoning has a hole in it, and the
 * reader would have no idea. Stopping is visible: the caller sends one closing line, then
 * {@code sources} and {@code done} as usual, so the stream still ends the way the contract
 * says (4.4).
 *
 * <p><strong>It cannot catch a wrong word.</strong> An answer calling a normal value "high"
 * contains no forbidden number. The prompt tells the model to trust the flags in
 * {@code <results>} rather than judge values itself, and the chat evaluation measures flag
 * contradictions directly. This guard is about numbers, and says so.
 *
 * <p>Stateful and single-use: one guard per answer. Not thread-safe, and does not need to be.
 */
public class AnswerGuard {

	/** Sent in place of the rest of an answer that quoted a number not on the report. */
	public static final String STOP_NOTICE =
			"I can only quote numbers that are on your report, so I've stopped this answer here.";

	private final AllowedNumbers allowed;
	private final StringBuilder buffer = new StringBuilder();

	private boolean stopped;

	public AnswerGuard(AllowedNumbers allowed) {
		this.allowed = allowed;
	}

	/**
	 * Feeds text and returns whatever complete sentences passed.
	 *
	 * <p>Returns an empty list while a sentence is still being assembled, and after the answer
	 * has been stopped - once stopped, nothing more is ever emitted.
	 */
	public List<String> accept(String text) {
		if (stopped || text.isEmpty()) {
			return List.of();
		}

		buffer.append(text);
		List<String> passed = new ArrayList<>();

		int end;
		while ((end = sentenceEnd(buffer)) >= 0) {
			String sentence = buffer.substring(0, end);
			buffer.delete(0, end);

			if (sentence.isBlank()) {
				continue;
			}
			if (!check(sentence, passed)) {
				return passed;
			}
		}
		return passed;
	}

	/**
	 * Checks whatever is left when the model stops.
	 *
	 * <p>The last sentence of an answer usually has no trailing space after its full stop, so
	 * without this it would never be recognised as complete and would never be sent.
	 */
	public List<String> finish() {
		if (stopped || buffer.isEmpty()) {
			return List.of();
		}
		String remainder = buffer.toString();
		buffer.setLength(0);

		List<String> passed = new ArrayList<>();
		if (!remainder.isBlank()) {
			check(remainder, passed);
		}
		return passed;
	}

	/** Whether the guard stopped this answer. The caller counts this and sends the notice. */
	public boolean stopped() {
		return stopped;
	}

	/**
	 * @return false when the sentence was rejected, which also sets {@link #stopped()}
	 */
	private boolean check(String sentence, List<String> passed) {
		if (!allowed.disallowedIn(sentence).isEmpty()) {
			// Never logged, here or anywhere: the sentence is the model's writing about
			// someone's report, and the offending number is part of it (CLAUDE.md rule 19).
			// ChatService records only that a stop happened.
			stopped = true;
			buffer.setLength(0);
			return false;
		}
		passed.add(sentence);
		return true;
	}

	/**
	 * The index just past the end of the first complete sentence, or -1.
	 *
	 * <p>A sentence ends at {@code .}, {@code !} or {@code ?} followed by whitespace, at a
	 * blank line, or at the end of the stream (handled by {@link #finish()}).
	 *
	 * <p>Two cases this gets right that a naive split on {@code "."} does not. <strong>A
	 * decimal point is not a full stop</strong> - splitting "your LDL is 5.4 mmol/L" at the
	 * point would check "5" and "4 mmol/L" separately, and 4 is a counting number, so an
	 * invented 5.4 would slip through in pieces. <strong>A list marker is not a full
	 * stop</strong> either: "1. Ask about..." would otherwise end a sentence after "1".
	 */
	private static int sentenceEnd(CharSequence text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c == '\n' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
				return i + 2;
			}
			if (c != '.' && c != '!' && c != '?') {
				continue;
			}
			if (i + 1 >= text.length()) {
				// Might be the end, might be mid-number. Wait for the next character rather
				// than guess; finish() handles a stream that stops here.
				return -1;
			}
			if (!Character.isWhitespace(text.charAt(i + 1))) {
				continue;
			}
			if (c == '.' && isInsideNumber(text, i)) {
				continue;
			}
			if (c == '.' && isListMarker(text, i)) {
				continue;
			}
			return i + 1;
		}
		return -1;
	}

	/** A point with a digit on both sides, as in {@code 5.4}. */
	private static boolean isInsideNumber(CharSequence text, int index) {
		return index > 0 && Character.isDigit(text.charAt(index - 1))
				&& index + 1 < text.length() && Character.isDigit(text.charAt(index + 1));
	}

	/**
	 * A point that ends a list number at the start of a line, as in {@code 1. Ask your doctor}.
	 *
	 * <p>Only at the start of a line, so "your result was 3. The range is..." still ends a
	 * sentence - that 3 is a value, not a marker.
	 */
	private static boolean isListMarker(CharSequence text, int index) {
		int start = index;
		while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
			start--;
		}
		if (start == index) {
			return false;
		}
		return start == 0 || text.charAt(start - 1) == '\n';
	}
}
