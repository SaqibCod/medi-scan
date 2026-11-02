package dev.saq.mediscan.eval;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

import dev.saq.mediscan.extract.ConfidenceIndex;
import dev.saq.mediscan.extract.ExtractedText;
import dev.saq.mediscan.extract.ValueTokenMatcher;
import dev.saq.mediscan.mask.ResultRowDetector;

/**
 * Scores what OCR read, without asking a model anything
 * ({@code docs/phase-4-lld.md} 10.3).
 *
 * <p><strong>Why this can be measured on its own.</strong> The model's job in this pipeline is
 * transcription from text that is already in front of it; if a value never made it into the OCR
 * text, no model can recover it, and if it did make it, the support check will accept it. So the
 * quality of the OCR text bounds the quality of the whole run, and bounding it costs no API
 * quota - which is what makes a parameter sweep affordable at all.
 *
 * <p><strong>The three numbers that matter, and why.</strong>
 *
 * <ul>
 * <li><strong>Recovery</strong> - did the printed value survive OCR exactly? A value that did
 * not is simply lost: the support check will drop whatever the model transcribes instead.</li>
 * <li><strong>Unclear</strong> - of the values that survived, how many does the confidence
 * threshold refuse to flag? This is the cost of safety, paid by every reader, and the reason the
 * threshold is tuned rather than guessed.</li>
 * <li><strong>Risky</strong> - of the values that did <em>not</em> survive, how many sit on a
 * line OCR was confident about anyway? These are the candidates for a confident wrong flag, the
 * worst failure this application can have. A threshold is only as good as its effect on this
 * number.</li>
 * </ul>
 */
final class OcrTextMetrics {

	private OcrTextMetrics() {
	}

	/** One case-and-variant's result, before any threshold is applied. */
	record PageScore(
			int expectedValues,
			int recoveredValues,
			int expectedRanges,
			int recoveredRanges,
			int expectedNames,
			int recoveredNames,
			int expectedPii,
			int recoveredPii,
			List<Double> recoveredConfidences,
			List<Double> missedLineConfidences,
			double meanConfidence,
			long ocrMillis,
			int chars) {

		/** Counts only, never the text. */
		@Override
		public String toString() {
			return "PageScore[values=" + recoveredValues + "/" + expectedValues
					+ ", chars=" + chars + ", ms=" + ocrMillis + "]";
		}
	}

	/**
	 * Scores one extraction against the case's answer key.
	 *
	 * @param extracted what the pipeline's extractor produced, confidences included
	 * @param expected the hand-written key, reused unchanged from the text case
	 */
	static PageScore score(ExtractedText extracted, ExpectedResult expected, long ocrMillis) {
		String text = extracted.text();
		ConfidenceIndex confidence = extracted.confidence();

		int recoveredValues = 0;
		int recoveredRanges = 0;
		int recoveredNames = 0;
		List<Double> recoveredConfidences = new java.util.ArrayList<>();
		List<Double> missedLineConfidences = new java.util.ArrayList<>();
		int expectedRanges = 0;

		for (ExpectedResult.ExpectedBiomarker row : expected.biomarkers()) {
			if (containsName(text, row.testName())) {
				recoveredNames++;
			}

			if (row.referenceRangeText() != null && !row.referenceRangeText().isBlank()) {
				expectedRanges++;
				if (ValueTokenMatcher.appearsIn(row.referenceRangeText(), text)) {
					recoveredRanges++;
				}
			}

			if (ValueTokenMatcher.appearsIn(row.rawValue(), text)) {
				recoveredValues++;
				confidence.lowestConfidenceFor(row.rawValue(), ResultRowDetector::isResultRow)
						.ifPresent(recoveredConfidences::add);
			}
			else {
				// The value was misread. What OCR thought of the line it misread is the whole
				// question: a confident misreading is the dangerous kind, and a doubtful one is
				// the rule working.
				lineConfidenceFor(text, confidence, row.testName())
						.ifPresent(missedLineConfidences::add);
			}
		}

		int recoveredPii = 0;
		for (ExpectedResult.PlantedPii pii : expected.pii()) {
			// Verbatim, because that is what the masking rules match on. A garbled name is a
			// name masking will miss - the leak this variant is really testing for.
			if (text.contains(pii.text())) {
				recoveredPii++;
			}
		}

		return new PageScore(
				expected.biomarkers().size(), recoveredValues,
				expectedRanges, recoveredRanges,
				expected.biomarkers().size(), recoveredNames,
				expected.pii().size(), recoveredPii,
				List.copyOf(recoveredConfidences), List.copyOf(missedLineConfidences),
				meanConfidenceOf(confidence, text), ocrMillis, text.length());
	}

	/**
	 * How sure OCR was about the numbers on the row a misread value belongs to.
	 *
	 * <p>Found by test name rather than by value, because the value is exactly the thing that is
	 * missing. The name usually survives when a digit does not - letters have far more
	 * redundancy than a lone numeral - which is what makes it a usable anchor.
	 */
	private static OptionalDouble lineConfidenceFor(String text, ConfidenceIndex confidence,
			String testName) {

		if (confidence.isEmpty() || testName == null) {
			return OptionalDouble.empty();
		}

		for (String line : text.split("\\R")) {
			if (!containsName(line, testName) || !ResultRowDetector.isResultRow(line)) {
				continue;
			}
			// Every numeric token on the row, at its measured confidence. The lowest of them is
			// what the rule would see if the model transcribed one of them as the value.
			OptionalDouble lowest = OptionalDouble.empty();
			for (String token : line.trim().split("\\s+")) {
				if (!token.matches(".*\\d.*")) {
					continue;
				}
				OptionalDouble tokenConfidence =
						confidence.lowestConfidenceFor(token, candidate -> true);
				if (tokenConfidence.isPresent()
						&& (lowest.isEmpty() || tokenConfidence.getAsDouble() < lowest.getAsDouble())) {
					lowest = tokenConfidence;
				}
			}
			if (lowest.isPresent()) {
				return lowest;
			}
		}
		return OptionalDouble.empty();
	}

	/**
	 * Whether a test name is present, allowing for the ways OCR shifts spacing and case.
	 *
	 * <p>Looser than the value check on purpose. A name is matched against a curated catalogue
	 * downstream and never parsed into a number, so {@code "HDL  Cholesterol"} is as good as
	 * {@code "HDL Cholesterol"} - whereas {@code 5.4} and {@code 54} are different results.
	 */
	private static boolean containsName(String text, String testName) {
		if (testName == null || testName.isBlank()) {
			return false;
		}
		return normalize(text).contains(normalize(testName));
	}

	private static String normalize(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	/** The mean over every value this case's key names, as a readability figure for the table. */
	private static double meanConfidenceOf(ConfidenceIndex confidence, String text) {
		if (confidence.isEmpty()) {
			return 100;
		}
		double total = 0;
		int counted = 0;
		for (String line : text.split("\\R")) {
			for (String token : line.trim().split("\\s+")) {
				if (token.isBlank()) {
					continue;
				}
				OptionalDouble tokenConfidence =
						confidence.lowestConfidenceFor(token, candidate -> true);
				if (tokenConfidence.isPresent()) {
					total += tokenConfidence.getAsDouble();
					counted++;
				}
			}
		}
		return counted == 0 ? 0 : total / counted;
	}

	/** The share of recovered values the threshold would mark unclear. */
	static double unclearRate(List<Double> recoveredConfidences, double threshold) {
		if (recoveredConfidences.isEmpty()) {
			return 0;
		}
		long unclear = recoveredConfidences.stream().filter(value -> value < threshold).count();
		return (double) unclear / recoveredConfidences.size();
	}

	/**
	 * The share of misread values the threshold would let through with a flag.
	 *
	 * <p>The gate number. Every one of these is a row where OCR got a digit wrong and was sure
	 * of it, so the rule lets the pipeline compute a flag from a number the report never
	 * printed.
	 */
	static double riskyRate(List<Double> missedLineConfidences, double threshold) {
		if (missedLineConfidences.isEmpty()) {
			return 0;
		}
		long risky = missedLineConfidences.stream().filter(value -> value >= threshold).count();
		return (double) risky / missedLineConfidences.size();
	}
}
