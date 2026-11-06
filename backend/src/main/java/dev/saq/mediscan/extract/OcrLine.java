package dev.saq.mediscan.extract;

import java.util.List;

/**
 * One line of text Tesseract read, with the words it was built from.
 *
 * <p>The line is the unit the confidence lookup works in, because a line is the thing masking
 * cannot change: {@code ResultRowDetector} protects whole lines, and {@code Masker} refuses any
 * span overlapping a protected one, so a result row is byte-identical before and after masking.
 * Searching for a value inside a line therefore needs no offset translation at all
 * ({@code docs/phase-4-lld.md} section 5).
 *
 * @param text the line as rebuilt from its words. Report content
 * @param words the words, in reading order, with offsets into {@link #text}
 */
public record OcrLine(String text, List<OcrWord> words) {

	public OcrLine(String text, List<OcrWord> words) {
		this.text = text;
		this.words = List.copyOf(words);
	}

	/**
	 * The lowest confidence among the words overlapping {@code [from, to)}, or empty when no
	 * word does.
	 *
	 * <p>The minimum, not the mean: a value is wrong if <em>any</em> of its characters was
	 * misread, so the least certain word decides. Averaging would let one confident digit
	 * vouch for a doubtful one beside it.
	 */
	java.util.OptionalDouble lowestConfidenceOverlapping(int from, int to) {
		return words.stream()
				.filter(word -> word.overlaps(from, to))
				.mapToDouble(OcrWord::confidence)
				.min();
	}

	/** Hides {@link #text()} and the words. */
	@Override
	public String toString() {
		return "OcrLine[chars=" + text.length() + ", words=" + words.size() + "]";
	}
}
