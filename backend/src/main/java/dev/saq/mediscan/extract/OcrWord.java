package dev.saq.mediscan.extract;

/**
 * One word Tesseract read, and how sure it was.
 *
 * <p>{@code start} and {@code end} are offsets into the text of the {@link OcrLine} this word
 * belongs to, not into the whole page. Line-local offsets are what make the confidence lookup
 * survive masking: masking rewrites spans outside result rows, which moves every page-level
 * offset, but it never touches a result row's line - so an offset inside one stays valid
 * ({@code docs/phase-4-lld.md} section 5).
 *
 * @param text the word as read. Report content
 * @param confidence Tesseract's confidence, 0 to 100. A <strong>double</strong>: the TSV
 *     {@code conf} column is a float ({@code 96.723816}), not the integer the design assumed
 * @param start offset of this word in its line's text, inclusive
 * @param end offset of this word in its line's text, exclusive
 * @param left pixel position in the source image, kept for the column-gap rule
 * @param width pixel width in the source image, kept for the column-gap rule
 */
public record OcrWord(String text, double confidence, int start, int end, int left, int width) {

	/**
	 * Average pixel width of one character in this word.
	 *
	 * <p>By code point, not by {@code char}: a word containing {@code µ} or an astral character
	 * would otherwise measure narrower per character than it really is, which would drag the
	 * line's median down and turn ordinary spaces into column gaps.
	 */
	double averageCharacterWidth() {
		int characters = text.codePointCount(0, text.length());
		return characters == 0 ? 0 : (double) width / characters;
	}

	/** Where this word ends in the image, for measuring the gap to the next one. */
	int right() {
		return left + width;
	}

	/** Whether this word overlaps the line-local range {@code [from, to)}. */
	boolean overlaps(int from, int to) {
		return start < to && from < end;
	}

	/**
	 * Hides {@link #text()}.
	 *
	 * <p>A word off a lab report is a patient's value or a patient's name. The position and the
	 * confidence are what logs and failure messages legitimately want.
	 */
	@Override
	public String toString() {
		return "OcrWord[chars=" + text.length() + ", confidence=" + Math.round(confidence)
				+ ", start=" + start + ", end=" + end + "]";
	}
}
