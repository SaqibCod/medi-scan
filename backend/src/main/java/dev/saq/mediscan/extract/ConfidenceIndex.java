package dev.saq.mediscan.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Predicate;

/**
 * How sure OCR was about each part of a report's text.
 *
 * <p>This is what lets the validator refuse to flag a number OCR may have misread. Ask it for a
 * printed value and it answers with the lowest confidence of any OCR word that value was read
 * from.
 *
 * <p><strong>Why it holds lines rather than offsets into the whole document.</strong> Masking
 * rewrites spans outside result rows, so every document-level offset moves - and the validator
 * works on the masked text, two transformations away from where OCR recorded its words. Lines
 * are immune to that: {@code ResultRowDetector} protects whole lines and {@code Masker} refuses
 * any span overlapping a protected one, so <em>a result row is byte-identical before and after
 * masking</em>. Searching for a value inside a line therefore needs no offset translation
 * between the two texts at all, which is why this approach was chosen over threading an offset
 * map through the masker ({@code docs/phase-4-lld.md} section 5).
 *
 * <p><strong>The minimum, not the mean.</strong> A value is wrong if any of its characters was
 * misread, so the least certain word decides. Averaging would let a confident digit vouch for a
 * doubtful one beside it, which is the exact failure the rule exists to prevent.
 */
public final class ConfidenceIndex {

	/** For text that did not come from OCR. Every lookup is empty, so nothing is doubted. */
	public static final ConfidenceIndex NONE = new ConfidenceIndex(List.of());

	private final List<OcrLine> lines;

	private ConfidenceIndex(List<OcrLine> lines) {
		this.lines = List.copyOf(lines);
	}

	/** An index over the lines of one or more OCR'd pages, in document order. */
	static ConfidenceIndex of(List<OcrPage> pages) {
		List<OcrLine> all = new ArrayList<>();
		for (OcrPage page : pages) {
			all.addAll(page.lines());
		}
		return all.isEmpty() ? NONE : new ConfidenceIndex(all);
	}

	/** Whether this index knows anything. Empty for text-layer and pasted reports. */
	public boolean isEmpty() {
		return lines.isEmpty();
	}

	/**
	 * The lowest confidence any occurrence of {@code value} was read with.
	 *
	 * <p>Result rows are searched first, because that is where a lab value legitimately appears
	 * and what the rule is defined over. If the value is found nowhere on a result row, every
	 * line is searched as a fallback - an occurrence anywhere in the OCR text has a real,
	 * measured confidence, and using it is strictly better informed than guessing. Only when
	 * the value cannot be located at all does this return empty, which the caller treats as
	 * "not to be trusted" and counts separately so a lookup bug shows up as a number rather
	 * than quietly passing values through unchecked (approved deviation D7).
	 *
	 * @param value the value as printed, exactly as the support check matched it
	 * @param isResultRow whether a line carries lab results. Passed in rather than decided here
	 *     so {@code extract} need not depend on {@code mask}
	 * @return the lowest confidence, or empty if {@code value} appears on no OCR line
	 */
	public OptionalDouble lowestConfidenceFor(String value, Predicate<String> isResultRow) {
		if (lines.isEmpty()) {
			return OptionalDouble.empty();
		}

		OptionalDouble onResultRows = lowestAcross(value, isResultRow);
		if (onResultRows.isPresent()) {
			return onResultRows;
		}
		return lowestAcross(value, line -> true);
	}

	private OptionalDouble lowestAcross(String value, Predicate<String> accept) {
		double lowest = Double.MAX_VALUE;
		boolean found = false;

		for (OcrLine line : lines) {
			if (line.text().isEmpty() || !accept.test(line.text())) {
				continue;
			}
			for (int[] occurrence : ValueTokenMatcher.occurrences(value, line.text())) {
				OptionalDouble confidence =
						line.lowestConfidenceOverlapping(occurrence[0], occurrence[1]);
				if (confidence.isPresent()) {
					lowest = Math.min(lowest, confidence.getAsDouble());
					found = true;
				}
			}
		}

		return found ? OptionalDouble.of(lowest) : OptionalDouble.empty();
	}

	/** Sizes only. The lines are report text. */
	@Override
	public String toString() {
		return "ConfidenceIndex[lines=" + lines.size() + ", words="
				+ lines.stream().mapToInt(line -> line.words().size()).sum() + "]";
	}
}
