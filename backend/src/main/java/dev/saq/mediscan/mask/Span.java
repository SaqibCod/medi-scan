package dev.saq.mediscan.mask;

/**
 * A half-open character range {@code [start, end)} of the original text, and what it holds.
 *
 * <p>Offsets into the <em>original</em> string throughout. Rules never see each other's
 * placeholders, because every rule runs against the same unmodified input and the replacement
 * happens once at the end. That rules out a whole class of bug where one rule's
 * {@code [NAME]} becomes another rule's input - and it is why the replacement pass has to work
 * right to left, so that earlier offsets stay valid as later text is substituted.
 *
 * @param start first character of the span
 * @param end one past the last character
 * @param type what was found, which decides the placeholder
 */
public record Span(int start, int end, MaskType type) implements Comparable<Span> {

	public Span {
		if (start < 0 || end < start) {
			throw new IllegalArgumentException("span [" + start + ", " + end + ") is not valid");
		}
	}

	public int length() {
		return end - start;
	}

	/** Whether this span shares at least one character with {@code other}. */
	public boolean overlaps(Span other) {
		return start < other.end && other.start < end;
	}

	/** Whether this span shares at least one character with {@code [otherStart, otherEnd)}. */
	public boolean overlaps(int otherStart, int otherEnd) {
		return start < otherEnd && otherStart < end;
	}

	/** By start, then by longest first, which is the order the replacement pass relies on. */
	@Override
	public int compareTo(Span other) {
		int byStart = Integer.compare(start, other.start);
		return byStart != 0 ? byStart : Integer.compare(other.end, end);
	}

	/**
	 * Offsets and type only - never the text at those offsets.
	 *
	 * <p>A span's whole purpose is to point at personal data, so printing its content would
	 * put exactly the wrong thing in a log line.
	 */
	@Override
	public String toString() {
		return "Span[" + start + ".." + end + ", " + type + "]";
	}
}
