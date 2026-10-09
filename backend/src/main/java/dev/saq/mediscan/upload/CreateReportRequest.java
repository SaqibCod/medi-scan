package dev.saq.mediscan.upload;

/**
 * The JSON body of {@code POST /api/reports} - options B and C of contract section 4.1.
 *
 * <p>Exactly one of {@code text} and {@code sampleId} must be set; both or neither is a
 * {@code 400 VALIDATION_ERROR}. That rule is in {@link UploadValidator} rather than bean
 * validation annotations, because it spans two fields and has to report which field is at
 * fault in the {@code errors} array.
 *
 * <p>{@code consent} is a boxed {@link Boolean} so "absent" and "false" can be told apart in
 * the error message. Both are rejected, but a caller who sent nothing is told the field is
 * required rather than that their value was wrong.
 *
 * @param text pasted report text
 * @param sampleId an id from {@code GET /api/samples}
 * @param consent must be {@code true}
 */
public record CreateReportRequest(String text, String sampleId, Boolean consent) {

	/** Hides {@link #text()}: this record is the pasted report, verbatim and unmasked. */
	@Override
	public String toString() {
		return "CreateReportRequest[textLength=" + (text != null ? text.length() : 0)
				+ ", sampleId=" + sampleId + ", consent=" + consent + "]";
	}
}
