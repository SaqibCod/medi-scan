package dev.saq.mediscan.analysis;

/**
 * A lab value after parsing: a number, a comparator, or neither.
 *
 * @param number the numeric part, or {@code null} when the value is qualitative
 *     ({@code Negative}) or unparseable
 * @param comparator the printed comparator, {@link Comparator#NONE} for a plain number
 */
public record ParsedValue(Double number, Comparator comparator) {

	private static final ParsedValue NOT_NUMERIC = new ParsedValue(null, Comparator.NONE);

	/** A value with no number in it: {@code Negative}, {@code Trace}, {@code --}. */
	public static ParsedValue notNumeric() {
		return NOT_NUMERIC;
	}

	public static ParsedValue of(double number, Comparator comparator) {
		return new ParsedValue(number, comparator);
	}

	public boolean isNumeric() {
		return number != null;
	}

	/**
	 * What gets stored in {@code biomarker.numeric_value}: the number only when it is a plain
	 * one.
	 *
	 * <p>A comparator value is deliberately stored as {@code null}. {@code <0.5} is not a
	 * measurement of 0.5, and plotting it as one would draw a trend line through a number the
	 * lab never reported ({@code LLD} 11.3).
	 */
	public Double storedNumber() {
		return comparator == Comparator.NONE ? number : null;
	}
}
