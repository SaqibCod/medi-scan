package dev.saq.mediscan.trends;

/**
 * How a {@code TrendPoint}'s {@code date} was chosen (contract section 5, section 9.1).
 *
 * <p>{@code COLLECTED} when the report printed a specimen collection date; {@code UPLOADED}
 * when it did not and the point is dated by the report's upload instant instead.
 */
public enum DateSource {
	COLLECTED,
	UPLOADED
}
