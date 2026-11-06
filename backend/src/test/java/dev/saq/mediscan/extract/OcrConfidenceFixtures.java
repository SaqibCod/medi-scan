package dev.saq.mediscan.extract;

import java.util.List;

/**
 * Builds a {@link ConfidenceIndex} for tests outside this package.
 *
 * <p>{@code ConfidenceIndex.of} is package-private on purpose - only the extractors build one -
 * but the low-confidence rule lives in {@code analysis}, and its tests need to say "OCR read
 * this text, and it was unsure about that word". This is the bridge, and it exists only in test
 * sources so the production API stays as narrow as it should be.
 */
public final class OcrConfidenceFixtures {

	private OcrConfidenceFixtures() {
	}

	/** OCR read {@code text} and was equally sure of every word. */
	public static ConfidenceIndex readAt(String text, double confidence) {
		return ConfidenceIndex.of(List.of(FakeOcrEngine.page(text, confidence)));
	}

	/**
	 * OCR read {@code text} confidently except for {@code word}.
	 *
	 * @param word the word it was unsure about, matched exactly as Tesseract would have
	 *     tokenised it on whitespace
	 */
	public static ConfidenceIndex readWithDoubtAbout(String text, String word,
			double lowConfidence, double otherConfidence) {

		return ConfidenceIndex.of(List.of(
				FakeOcrEngine.pageWithLowConfidenceWord(text, word, lowConfidence,
						otherConfidence)));
	}
}
