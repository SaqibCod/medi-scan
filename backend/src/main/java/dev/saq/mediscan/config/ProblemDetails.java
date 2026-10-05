package dev.saq.mediscan.config;

import java.net.URI;
import java.util.List;

import org.springframework.http.ProblemDetail;

/**
 * Builds the one error shape the whole API returns: RFC 9457 Problem Details plus the
 * {@code code} and {@code requestId} fields Medi-Scan adds (contract section 1.5).
 *
 * <p>Shared deliberately. Errors reach the client down two different paths - thrown
 * exceptions via {@link ApiExceptionHandler}, and Spring Security rejections via
 * {@link ProblemAuthenticationEntryPoint} / {@link ProblemAccessDeniedHandler}, which never
 * reach a {@code @RestControllerAdvice}. Both render through here so a 401 from the filter
 * chain is indistinguishable in shape from a 404 from a controller.
 */
public final class ProblemDetails {

	private ProblemDetails() {
	}

	/** One invalid field, for the {@code errors} array on a {@code VALIDATION_ERROR}. */
	public record FieldError(String field, String message) {
	}

	public static ProblemDetail of(ErrorCode code, String detail) {
		return of(code, detail, null);
	}

	/**
	 * @param fieldErrors rendered as the {@code errors} array; omitted when null or empty
	 */
	public static ProblemDetail of(ErrorCode code, String detail, List<FieldError> fieldErrors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
		problem.setType(URI.create(code.type()));
		problem.setTitle(code.title());
		problem.setProperty("code", code.name());
		// Always present, so a user can quote it and it can be grepped in the logs.
		problem.setProperty("requestId", RequestIdFilter.currentRequestId());
		if (fieldErrors != null && !fieldErrors.isEmpty()) {
			problem.setProperty("errors", fieldErrors);
		}
		return problem;
	}
}
