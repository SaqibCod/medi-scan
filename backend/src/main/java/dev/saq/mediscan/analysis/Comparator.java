package dev.saq.mediscan.analysis;

/**
 * The comparator printed in front of a lab value, if any.
 *
 * <p>Lab reports print {@code <0.5} when the true value is below what the assay can measure.
 * That is not the same as the value being 0.5, which is why the comparator is kept separate
 * from the number instead of being thrown away: it decides whether the value can be flagged,
 * and whether it can be a point on a trend line.
 */
public enum Comparator {

	/** A plain number. */
	NONE,

	/** {@code <} - below the limit of detection. */
	LT,

	/** {@code ≤} */
	LE,

	/** {@code >} - above the measurable range. */
	GT,

	/** {@code ≥} */
	GE;

	public boolean isLess() {
		return this == LT || this == LE;
	}

	public boolean isGreater() {
		return this == GT || this == GE;
	}
}
