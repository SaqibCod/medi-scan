package dev.saq.mediscan.chat;

import java.util.Locale;

/**
 * Reads the decision tag off the front of a streamed answer ({@code LLD} 6.3).
 *
 * <p>The model is told to open every answer with exactly one of {@code [[ANSWER]]},
 * {@code [[DECLINE]]} or {@code [[NOT_IN_CONTEXT]]}. That tag is how the server knows, by the
 * end of the stream, which {@code finishReason} the contract's {@code done} event should carry
 * - short of grading the finished text with a second model, which would cost another call and
 * be less reliable than asking.
 *
 * <p><strong>The tag arrives split.</strong> Verified against the live API on 2026-11-03: a
 * five-chunk answer began {@code "[[ANSWER"} and then {@code "]] Your LDL cholesterol is..."}.
 * So this buffers across chunk boundaries rather than inspecting each chunk, and gives up
 * after {@link #TAG_SCAN_LIMIT} characters so a model that forgets the tag does not hold the
 * whole answer hostage.
 *
 * <p><strong>The tag never reaches the client.</strong> Neither the opening one nor a repeat
 * later in the text, which models do produce when they restate their reasoning. The contract's
 * {@code token} events carry the answer a person reads, and {@code [[ANSWER]]} is not part of
 * it.
 *
 * <p>Stateful and single-use: one parser per answer. Not thread-safe, and does not need to be -
 * a stream is consumed by one subscriber in order.
 */
public class TagParser {

	/**
	 * Characters to wait for a tag before concluding there is not one.
	 *
	 * <p>Longer than {@code [[NOT_IN_CONTEXT]]} (18 characters) with room for leading
	 * whitespace, and short enough that a tagless answer starts flowing almost immediately.
	 */
	static final int TAG_SCAN_LIMIT = 24;

	/** What the model decided this answer was. */
	public enum Decision {

		/** A normal answer. Maps to {@code finishReason: "COMPLETE"}. */
		ANSWER,

		/** A diagnosis or treatment question, declined. Maps to {@code "DECLINED"}. */
		DECLINE,

		/** Not in the report or the pages. Maps to {@code "NOT_IN_CONTEXT"}. */
		NOT_IN_CONTEXT;

		static Decision of(String tagBody) {
			return switch (tagBody.toUpperCase(Locale.ROOT)) {
				case "DECLINE" -> DECLINE;
				case "NOT_IN_CONTEXT" -> NOT_IN_CONTEXT;
				default -> ANSWER;
			};
		}

		/** The contract's {@code done.finishReason} value (4.4). */
		public String finishReason() {
			return switch (this) {
				case ANSWER -> "COMPLETE";
				case DECLINE -> "DECLINED";
				case NOT_IN_CONTEXT -> "NOT_IN_CONTEXT";
			};
		}
	}

	private final StringBuilder pending = new StringBuilder();

	private Decision decision;
	private boolean resolved;
	private boolean tagWasMissing;

	/**
	 * Whether the answer's first visible character is still to come.
	 *
	 * <p>Needed because the tag and the space after it can land in different chunks. Feeding
	 * {@code "[[DECLINE]]"} then {@code " Ask your doctor."} resolves the tag with nothing left
	 * over, so the space arrives as the start of the next chunk and would open the answer with
	 * a stray blank - while the same answer in one chunk would not. The client appends token
	 * events verbatim, so this is the difference between " Ask" and "Ask" on screen.
	 */
	private boolean awaitingFirstVisibleCharacter = true;

	/**
	 * Feeds one chunk and returns the text that may be shown.
	 *
	 * <p>Returns an empty string while still waiting for the tag. Everything returned has had
	 * the opening tag removed, and any repeated tag stripped out.
	 */
	public String accept(String chunk) {
		if (resolved) {
			return emit(stripRepeatedTags(chunk));
		}

		pending.append(chunk);
		String buffered = pending.toString();
		String leading = buffered.stripLeading();

		int close = leading.indexOf("]]");
		if (leading.startsWith("[[") && close > 0) {
			resolve(Decision.of(leading.substring(2, close)), false);
			return emit(stripRepeatedTags(leading.substring(close + 2)));
		}

		// Not a tag at all, or not yet a complete one.
		boolean cannotBecomeATag = !leading.isEmpty() && !leading.startsWith("[")
				&& !"[".equals(leading);
		if (cannotBecomeATag || buffered.length() >= TAG_SCAN_LIMIT) {
			// A missing tag counts as ANSWER and is counted, because it is the model drifting
			// from its instructions and worth knowing about - not a reason to drop the answer.
			resolve(Decision.ANSWER, true);
			return emit(stripRepeatedTags(buffered));
		}

		return "";
	}

	/**
	 * Flushes anything still buffered when the stream ends.
	 *
	 * <p>A very short answer can finish before the scan limit is reached, which would otherwise
	 * strand its text in the buffer and send an empty answer.
	 */
	public String finish() {
		if (resolved) {
			return "";
		}
		String buffered = pending.toString();
		String leading = buffered.stripLeading();

		int close = leading.indexOf("]]");
		if (leading.startsWith("[[") && close > 0) {
			resolve(Decision.of(leading.substring(2, close)), false);
			return emit(stripRepeatedTags(leading.substring(close + 2)));
		}

		resolve(Decision.ANSWER, true);
		return emit(stripRepeatedTags(buffered));
	}

	/** What the model decided. {@code ANSWER} until something says otherwise. */
	public Decision decision() {
		return decision != null ? decision : Decision.ANSWER;
	}

	/**
	 * Whether the model omitted its tag.
	 *
	 * <p>Logged as a count. A model that starts skipping the tag is a prompt regression, and
	 * the only symptom would otherwise be every answer quietly reporting {@code COMPLETE}.
	 */
	public boolean tagWasMissing() {
		return tagWasMissing;
	}

	/**
	 * Drops whitespace until the answer's first real character, then passes everything through.
	 *
	 * <p>Only the leading run: whitespace inside the answer is the model's paragraphing and the
	 * client renders it as markdown.
	 */
	private String emit(String text) {
		if (!awaitingFirstVisibleCharacter) {
			return text;
		}
		String trimmed = text.stripLeading();
		if (!trimmed.isEmpty()) {
			awaitingFirstVisibleCharacter = false;
		}
		return trimmed;
	}

	private void resolve(Decision value, boolean missing) {
		decision = value;
		tagWasMissing = missing;
		resolved = true;
		pending.setLength(0);
	}

	/**
	 * Removes any further {@code [[...]]} from mid-answer text.
	 *
	 * <p>Bounded to the three known tags rather than any bracketed word: the answer may
	 * legitimately quote something in double brackets, and removing arbitrary text because it
	 * looked like a tag would silently corrupt an answer.
	 */
	private static String stripRepeatedTags(String text) {
		if (text.indexOf("[[") < 0) {
			return text;
		}
		return text.replace("[[ANSWER]]", "")
				.replace("[[DECLINE]]", "")
				.replace("[[NOT_IN_CONTEXT]]", "");
	}
}
