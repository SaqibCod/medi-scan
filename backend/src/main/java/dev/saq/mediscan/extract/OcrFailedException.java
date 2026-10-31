package dev.saq.mediscan.extract;

/**
 * OCR could not read a page.
 *
 * <p>Carries a {@link Reason} and nothing else. No message built from the process's output, no
 * file path, no partial text: this exception travels up through logging, and everything OCR
 * touches is report content ({@code CLAUDE.md} rule 3).
 *
 * <p>It is deliberately not a {@code ReportFailure}. Whether a page failing means the whole
 * report fails depends on what was being read - a photo has only one page, while a scanned PDF
 * can lose one page and still have enough text to be worth analysing - so the extractor maps
 * this to a report failure code, not the engine.
 */
class OcrFailedException extends RuntimeException {

	/** Why the page failed. Safe to log: these are the only values. */
	enum Reason {

		/** The program is missing or not executable. */
		START_FAILED,

		/** The page overran {@code ocr.page-timeout} and the process was killed. */
		TIMEOUT,

		/** The process printed more than {@code ocr.max-output-bytes} and was killed. */
		OUTPUT_CAP,

		/** The process ended with a non-zero exit code. */
		EXIT_CODE,

		/** The job thread was interrupted, almost always a shutdown. */
		INTERRUPTED
	}

	private final Reason reason;

	OcrFailedException(Reason reason) {
		// No cause and no stack trace: this is an expected outcome on a bad image, not a bug,
		// and a stack trace per failed page is noise in a log that has to stay readable.
		super("ocr failed: " + reason, null, false, false);
		this.reason = reason;
	}

	Reason reason() {
		return reason;
	}
}
