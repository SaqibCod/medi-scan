package dev.saq.mediscan.analysis;

/**
 * A parsed reference range.
 *
 * <p>Either bound may be absent: {@code <200} has only an upper bound, {@code >60} only a
 * lower one. Inclusivity matters because it decides the flag for a value sitting exactly on a
 * bound - {@code 200} against {@code <200} is {@code HIGH}, but against {@code ≤200} it is
 * {@code NORMAL}.
 *
 * @param low lower bound, or {@code null}
 * @param high upper bound, or {@code null}
 * @param lowInclusive whether a value equal to {@code low} is inside the range
 * @param highInclusive whether a value equal to {@code high} is inside the range
 */
public record Range(Double low, Double high, boolean lowInclusive, boolean highInclusive) {

	/** {@code 3.5-5.0}: both bounds, both inclusive. */
	public static Range between(double low, double high) {
		return new Range(low, high, true, true);
	}

	/** {@code <200} or {@code ≤200}. */
	public static Range upTo(double high, boolean inclusive) {
		return new Range(null, high, false, inclusive);
	}

	/** {@code >60} or {@code ≥60}. */
	public static Range from(double low, boolean inclusive) {
		return new Range(low, null, inclusive, false);
	}

	public boolean hasLow() {
		return low != null;
	}

	public boolean hasHigh() {
		return high != null;
	}
}
