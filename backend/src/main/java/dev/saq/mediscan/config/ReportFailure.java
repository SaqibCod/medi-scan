package dev.saq.mediscan.config;

/**
 * An expected failure of the processing pipeline, carrying the code to store on the report.
 *
 * <p>Thrown by any pipeline stage that can fail in a way the user should be told about, and
 * caught once in the job runner, which turns it into {@code status = FAILED} plus
 * {@code error_code}.
 *
 * <p>Carries no message on purpose. A pipeline exception is the single most likely place for
 * report text to leak into a log line, so there is nowhere to put it: the code is the whole
 * payload. The constructor does not accept a detail string, which makes that structural
 * rather than a rule to remember ({@code backend/CLAUDE.md}, "Privacy in code").
 *
 * <p>No stack trace either. These are control flow, not bugs, and they are raised once per
 * failed report on a 2 GB box.
 */
public class ReportFailure extends RuntimeException {

	private final ReportErrorCode code;

	public ReportFailure(ReportErrorCode code) {
		super(code.name(), null, false, false);
		this.code = code;
	}

	public ReportErrorCode code() {
		return code;
	}
}
