package dev.saq.mediscan.analysis;

import java.util.Optional;

import dev.saq.mediscan.report.Flag;

/**
 * Decides whether a value is low, normal, high, or not comparable.
 *
 * <p>Always computed here, never read from the model ({@code CLAUDE.md} rule 4). This is the
 * one number-shaped thing a patient reads as a judgement, so it is derived from the printed
 * range by code that can be tested on every boundary, rather than from a model that might get
 * it backwards.
 *
 * <p>The guiding rule is that {@code UNKNOWN} is always an acceptable answer. A value with no
 * readable range, or a comparator value that straddles a bound, is shown as text with its
 * printed range next to it - which is what the paper report says too. Guessing a flag would
 * be presenting an inference as a measurement.
 *
 * <p>There is no {@code CRITICAL}. Critical values use separate cutoffs that cannot be derived
 * from a reference range ({@code docs/plan.md} section 4.4).
 */
public final class FlagCalculator {

	private FlagCalculator() {
	}

	/** The flag for {@code value} against {@code range}. */
	public static Flag calculate(ParsedValue value, Optional<Range> range) {
		if (value == null || range.isEmpty() || !value.isNumeric()) {
			// No range to compare against, or a qualitative value such as "Negative".
			return Flag.UNKNOWN;
		}

		Range bounds = range.get();
		double number = value.number();

		return switch (value.comparator()) {
			case NONE -> flagPlainValue(number, bounds);
			case LT, LE -> flagLessThan(number, bounds);
			case GT, GE -> flagGreaterThan(number, bounds);
		};
	}

	private static Flag flagPlainValue(double number, Range bounds) {
		if (bounds.hasLow() && isBelow(number, bounds)) {
			return Flag.LOW;
		}
		if (bounds.hasHigh() && isAbove(number, bounds)) {
			return Flag.HIGH;
		}
		return Flag.NORMAL;
	}

	/**
	 * A value printed as {@code <x}: the true value is somewhere below {@code x}.
	 *
	 * <p>Only conclusive when the whole interval below {@code x} is below the range's lower
	 * bound. {@code <0.5} against {@code 0.45-4.50} could be either side of 0.45, so it is
	 * {@code UNKNOWN} - the honest answer, and the one the lab itself is giving.
	 */
	private static Flag flagLessThan(double number, Range bounds) {
		if (bounds.hasLow() && number <= bounds.low()) {
			return Flag.LOW;
		}
		return Flag.UNKNOWN;
	}

	/** A value printed as {@code >x}: conclusive only when {@code x} already reaches the top. */
	private static Flag flagGreaterThan(double number, Range bounds) {
		if (bounds.hasHigh() && number >= bounds.high()) {
			return Flag.HIGH;
		}
		return Flag.UNKNOWN;
	}

	private static boolean isBelow(double number, Range bounds) {
		return bounds.lowInclusive() ? number < bounds.low() : number <= bounds.low();
	}

	private static boolean isAbove(double number, Range bounds) {
		return bounds.highInclusive() ? number > bounds.high() : number >= bounds.high();
	}
}
