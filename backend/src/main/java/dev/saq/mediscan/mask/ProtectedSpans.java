package dev.saq.mediscan.mask;

import java.util.List;

/**
 * The spans of text masking must not touch, with a lookup for overlap checks.
 *
 * <p>A dedicated type rather than a bare {@code List<Span>} so the question every rule asks -
 * "may I touch this range?" - has one implementation. The spans are sorted and
 * non-overlapping by construction (one per line), which is what lets
 * {@link #overlapsAny(int, int)} binary-search instead of scanning: masking runs a dozen rules
 * over a document that can have hundreds of result rows, and a linear scan per candidate span
 * is the one part of this pipeline that would show up on a 2 GB box.
 */
public final class ProtectedSpans {

	private static final ProtectedSpans NONE = new ProtectedSpans(List.of());

	/** Sorted by start, non-overlapping. */
	private final List<Span> spans;

	ProtectedSpans(List<Span> spans) {
		this.spans = List.copyOf(spans);
	}

	/** No protected spans, for rules and tests that need a neutral value. */
	public static ProtectedSpans none() {
		return NONE;
	}

	public List<Span> spans() {
		return spans;
	}

	public int count() {
		return spans.size();
	}

	/** Whether {@code [start, end)} shares any character with a protected span. */
	public boolean overlapsAny(int start, int end) {
		if (spans.isEmpty() || start >= end) {
			return false;
		}

		// Find the last span starting at or before `start`; it is the only candidate that can
		// begin earlier and still reach into the range, because spans do not overlap.
		int low = 0;
		int high = spans.size() - 1;
		int candidate = -1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (spans.get(mid).start() <= start) {
				candidate = mid;
				low = mid + 1;
			}
			else {
				high = mid - 1;
			}
		}

		if (candidate >= 0 && spans.get(candidate).overlaps(start, end)) {
			return true;
		}
		// And the next span along, which may start inside the range.
		int next = candidate + 1;
		return next < spans.size() && spans.get(next).overlaps(start, end);
	}

	/** Whether {@code span} overlaps anything protected. */
	public boolean overlapsAny(Span span) {
		return overlapsAny(span.start(), span.end());
	}

	/** Whether the character at {@code index} is inside a protected span. */
	public boolean contains(int index) {
		return overlapsAny(index, index + 1);
	}

	@Override
	public String toString() {
		return "ProtectedSpans[count=" + spans.size() + "]";
	}
}
