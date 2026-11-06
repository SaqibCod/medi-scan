package dev.saq.mediscan.mask;

import java.util.List;

/**
 * One way of finding personal data in report text.
 *
 * <p>A rule reports candidate spans and decides nothing. {@link Masker} owns the arbitration:
 * which spans survive, which overlap a protected result row, and how the text is rewritten.
 * Keeping rules free of that logic is what makes each one testable in isolation with a table
 * of inputs, and what lets the order they run in be a property of the pipeline rather than
 * something each rule has to know.
 *
 * <p>Rules receive {@code protectedSpans} so they can decline early where that is cheaper or
 * more accurate than being overruled afterwards - the phone rule in particular, because a long
 * digit run in a result row looks exactly like a phone number.
 */
public interface MaskRule {

	/** A short name for logs and conflict counts. Never includes matched text. */
	String name();

	/**
	 * Candidate spans in {@code text}, as offsets into that same string.
	 *
	 * <p>May return overlapping spans; the masker resolves them. Must not return spans outside
	 * the text's bounds.
	 */
	List<Span> find(String text, ProtectedSpans protectedSpans);
}
