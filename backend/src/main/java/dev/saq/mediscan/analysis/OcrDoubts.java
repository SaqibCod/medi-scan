package dev.saq.mediscan.analysis;

import java.util.OptionalDouble;

import dev.saq.mediscan.extract.ConfidenceIndex;
import dev.saq.mediscan.mask.ResultRowDetector;

/**
 * Whether OCR read a printed string well enough for the application to compute anything from it.
 *
 * <p>This is the gatekeeper for {@code CLAUDE.md} rule 4's OCR half: <em>never trust an OCR
 * number either</em>. OCR misreads in ways that stay plausible - {@code 5.4} becomes {@code 54},
 * a decimal point vanishes, {@code 0.8} becomes {@code O.8} - and a misread number that still
 * parses gets a confident flag computed from it. A reader then sees {@code HIGH} beside a number
 * their report never printed, which is the worst failure this application can have
 * ({@code docs/phase-4-lld.md} sections 1 and 7).
 *
 * <p>So a doubtful string keeps its printed text and loses everything derived from it. The text
 * was really on the page - it is only our reading of the characters that is uncertain - and
 * showing it unflagged lets the reader check it against the paper, which is exactly what the
 * results page asks them to do.
 *
 * <p><strong>A value that cannot be located is doubtful, not trusted.</strong> The support check
 * has already proved the value appears in the masked text, so a lookup that then finds it on no
 * OCR line means the two searches disagreed - a bug in this code, not a property of the report.
 * Treating that as "confident" would silently switch the rule off for the affected rows, so it
 * is treated as doubt and counted separately, where a non-zero figure is visible rather than
 * invisible.
 *
 * <p>One instance per report: it accumulates that report's counts, and the validator is a
 * singleton.
 */
final class OcrDoubts {

	/** For a report whose text came from a PDF text layer or was pasted. Doubts nothing. */
	static final OcrDoubts NONE = new OcrDoubts(ConfidenceIndex.NONE, 0);

	private final ConfidenceIndex confidence;
	private final double minConfidence;

	private int doubtedRows;
	private int unlocatedValues;

	private OcrDoubts(ConfidenceIndex confidence, double minConfidence) {
		this.confidence = confidence;
		this.minConfidence = minConfidence;
	}

	/**
	 * @param confidence what OCR recorded, or {@link ConfidenceIndex#NONE}
	 * @param minConfidence {@code ocr.min-value-confidence}: below this, nothing is derived
	 */
	static OcrDoubts of(ConfidenceIndex confidence, double minConfidence) {
		return confidence.isEmpty() ? NONE : new OcrDoubts(confidence, minConfidence);
	}

	/** Whether this report has any OCR text at all. False means every check below is a no-op. */
	boolean apply() {
		return !confidence.isEmpty();
	}

	/**
	 * Whether {@code printed} was read too uncertainly to derive a number, a range or a date
	 * from.
	 *
	 * <p>Always false for a report with no OCR text, so a text-layer report takes exactly the
	 * phase-3 path through the validator.
	 */
	boolean about(String printed) {
		if (confidence.isEmpty() || printed == null || printed.isBlank()) {
			return false;
		}

		OptionalDouble lowest =
				confidence.lowestConfidenceFor(printed, ResultRowDetector::isResultRow);
		if (lowest.isEmpty()) {
			unlocatedValues++;
			return true;
		}
		return lowest.getAsDouble() < minConfidence;
	}

	/** Records that the rule changed one row. Counted per row, matching the eval's unclear rate. */
	void countDoubtedRow() {
		doubtedRows++;
	}

	/** How many rows the rule turned {@code UNKNOWN}. */
	int doubtedRows() {
		return doubtedRows;
	}

	/** How many strings passed the support check but could be found on no OCR line. */
	int unlocatedValues() {
		return unlocatedValues;
	}
}
