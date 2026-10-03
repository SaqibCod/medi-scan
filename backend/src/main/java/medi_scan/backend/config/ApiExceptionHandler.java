package medi_scan.backend.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place HTTP errors are rendered, as RFC 9457 Problem Details with the
 * {@code code} and {@code requestId} fields from contract section 1.5.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring's own exceptions (unreadable
 * body, wrong method, wrong media type) come out in the same shape instead of Spring's
 * default, which carries no {@code code}.
 *
 * <p>Errors raised inside the Spring Security filter chain never reach a
 * {@code @RestControllerAdvice}; those are handled by {@link ProblemAuthenticationEntryPoint}
 * and {@link ProblemAccessDeniedHandler}, which build their bodies with the same
 * {@link ProblemDetails} helper.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/** Everything that maps onto a contract error code. */
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
		ProblemDetail problem = ProblemDetails.of(ex.code(), ex.getMessage());

		ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.code().status());
		if (ex.retryAfterSeconds() != null) {
			// Contract section 1.6: a 429 always says how long to wait.
			response.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
		}
		return response.body(problem);
	}

	/** A path variable or query parameter of the wrong type, such as a malformed UUID. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		List<ProblemDetails.FieldError> errors =
				List.of(new ProblemDetails.FieldError(ex.getName(), "is not a valid value"));
		return ResponseEntity.badRequest()
				.body(ProblemDetails.of(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", errors));
	}

	/** Bean validation on a request body. */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		List<ProblemDetails.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new ProblemDetails.FieldError(
						fieldError.getField(),
						fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "is invalid"))
				.toList();

		return ResponseEntity.badRequest()
				.body(ProblemDetails.of(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", errors));
	}

	/** Bean validation on individual handler parameters. */
	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		List<ProblemDetails.FieldError> errors = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new ProblemDetails.FieldError(
								result.getMethodParameter().getParameterName(),
								error.getDefaultMessage() != null ? error.getDefaultMessage() : "is invalid")))
				.toList();

		return ResponseEntity.badRequest()
				.body(ProblemDetails.of(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", errors));
	}

	/**
	 * Gives every exception Spring MVC handles itself a {@code code} and {@code requestId},
	 * so no error response can escape without them.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

		ErrorCode code = statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_ERROR;
		String detail = code == ErrorCode.INTERNAL_ERROR
				? "Something went wrong. Quote the requestId when reporting this."
				: "The request could not be processed.";

		return ResponseEntity.status(statusCode).body(ProblemDetails.of(code, detail));
	}

	/**
	 * Last resort. The client gets a generic message and a request id, never the exception's
	 * own message: an exception thrown from the processing pipeline could quote report text,
	 * and leaking it here would break the privacy rules in {@code docs/dataflow.md} section
	 * 10. Pipeline code must not put report text in exception messages either.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		log.error("Unhandled exception of type {}", ex.getClass().getName(), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ProblemDetails.of(ErrorCode.INTERNAL_ERROR,
						"Something went wrong. Quote the requestId when reporting this."));
	}
}
