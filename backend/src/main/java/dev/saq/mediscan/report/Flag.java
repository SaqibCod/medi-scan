package dev.saq.mediscan.report;

/**
 * Where a value sits relative to its printed reference range.
 *
 * <p>Always computed in code by {@code FlagCalculator}, never requested from or trusted from
 * the model ({@code CLAUDE.md} rule 4).
 *
 * <p>There is no {@code CRITICAL}. Critical values use separate cutoffs that cannot be
 * derived from a reference range, so inventing one from the range would be a clinical claim
 * the data does not support ({@code docs/plan.md} section 4.4).
 */
public enum Flag {

	/** Below the parsed lower bound. */
	LOW,

	/** Inside both parsed bounds. */
	NORMAL,

	/** Above the parsed upper bound. */
	HIGH,

	/**
	 * Not comparable. The value is qualitative ({@code Negative}), or the range could not be
	 * parsed, or it depends on sex or age.
	 */
	UNKNOWN
}
