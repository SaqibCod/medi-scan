package dev.saq.mediscan.config;

import java.util.List;

/**
 * A {@code 400 VALIDATION_ERROR} that names the fields at fault.
 *
 * <p>Separate from {@link ApiException} because it carries the {@code errors} array from
 * contract section 1.5. Bean validation already produces that shape for annotated request
 * bodies; this is for rules annotations cannot express - a field whose validity depends on
 * another field, or on configuration.
 *
 * <p>Field messages reach the client, so they must describe the rule rather than echo the
 * value. "must be at least 20 characters" is fine; quoting the text back would put report
 * content in an error response.
 */
public class ValidationException extends RuntimeException {

	private final List<ProblemDetails.FieldError> fieldErrors;

	public ValidationException(String detail, List<ProblemDetails.FieldError> fieldErrors) {
		super(detail, null, false, false);
		this.fieldErrors = List.copyOf(fieldErrors);
	}

	public List<ProblemDetails.FieldError> fieldErrors() {
		return fieldErrors;
	}
}
