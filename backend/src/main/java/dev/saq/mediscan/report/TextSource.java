package dev.saq.mediscan.report;

/**
 * How a report's text was obtained.
 *
 * <p>Separate from {@link SourceType} because that answers a different question and cannot
 * answer this one: a scanned PDF arrives as {@code SourceType.PDF} and comes out as
 * {@link #OCR}, and a photo arrives as {@code SourceType.IMAGE} and also comes out as
 * {@link #OCR}. What the client needs to know is whether a human-readable number on the screen
 * was transcribed by a machine that guesses, because then it should ask the reader to check it
 * against the paper (contract 4.2).
 *
 * <p>Lives in {@code report} rather than {@code extract}: it is persisted on the report row and
 * returned by the API, and {@code extract} already depends on this package for
 * {@link SourceType}.
 *
 * <p>A closed set of three. The contract types it as a closed union and the database has a
 * check constraint, so a fourth value is a breaking API change, not a quiet addition.
 */
public enum TextSource {

	/** Straight from a PDF's text layer, from pasted text, or from a bundled sample. */
	TEXT_LAYER,

	/** Every page with text on it was read by OCR. */
	OCR,

	/** Some pages came from a text layer and some from OCR. */
	MIXED;

	/**
	 * What to report for a stored value that may be null.
	 *
	 * <p>Null means the report predates phase 4, when there was no OCR path at all - so its
	 * text came from a text layer or was pasted, and {@link #TEXT_LAYER} is a statement of fact
	 * rather than a default. A failed report also stores null, but nothing asks this question
	 * about one: {@code textSource} is only read for a {@code DONE} report.
	 */
	public static TextSource orTextLayer(TextSource stored) {
		return stored == null ? TEXT_LAYER : stored;
	}

	/** Whether values from this source were transcribed by OCR and may be misread. */
	public boolean involvesOcr() {
		return this != TEXT_LAYER;
	}
}
