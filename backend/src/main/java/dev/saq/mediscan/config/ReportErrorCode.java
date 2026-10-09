package dev.saq.mediscan.config;

/**
 * Every report <em>processing</em> failure code in {@code docs/api-contract.md} section 8.2.
 *
 * <p>A separate set from {@link dev.saq.mediscan.config.ErrorCode}, and deliberately so.
 * These are not HTTP errors: they are stored on the report in {@code report.error_code} and
 * returned inside the {@code error} object of a {@code 200} response, because by the time a
 * job fails the upload request has long since returned {@code 202}.
 *
 * <p>The {@code message} is the text contract section 8.2 specifies. The client may use its
 * own wording keyed on the code instead, which is why the code is the stable part.
 *
 * <p>Adding a code here without adding it to the contract first is a contract violation.
 */
public enum ReportErrorCode {

	/**
	 * No usable text, even after OCR. In phase 2 this also covers a PDF with no text layer
	 * and an encrypted PDF, because there is no OCR fallback to try yet.
	 */
	UNREADABLE("We couldn't read text from this file. Try a clearer image."),

	/** Extracted text is over the character or page limit. */
	DOCUMENT_TOO_LONG("This document is too long to process. Try a shorter report."),

	/** No lab values survived validation. */
	NO_RESULTS_FOUND("We didn't find lab results in this document."),

	/** The model returned invalid output twice, or the summary came back blank. */
	EXTRACTION_FAILED("Something went wrong reading the results. Please try again."),

	/** The daily LLM cap was reached mid-job. */
	CAPACITY("The demo has hit today's limit. Try again tomorrow."),

	/** The LLM provider failed after retries. */
	LLM_UNAVAILABLE("The AI service is unavailable right now. Please try again later."),

	/** The server restarted during processing, so the job cannot be resumed. */
	INTERRUPTED("Processing was interrupted. Please upload again.");

	private final String message;

	ReportErrorCode(String message) {
		this.message = message;
	}

	/** The {@code error.message} text from contract section 8.2. */
	public String message() {
		return message;
	}
}
