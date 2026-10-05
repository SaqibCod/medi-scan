package dev.saq.mediscan.config;

/**
 * An error that maps directly onto one {@link ErrorCode} from the API contract.
 *
 * <p>Thrown by handlers and services; rendered by {@link ApiExceptionHandler}.
 *
 * <p>The message is the Problem Details {@code detail} and goes to the client, so it must
 * never contain report text, a token, or anything else from {@code docs/dataflow.md} section
 * 10. Pass ids and codes, not content.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	/** Seconds the client should wait before retrying, or {@code null} when not a 429. */
	private final Long retryAfterSeconds;

	public ApiException(ErrorCode code, String detail) {
		this(code, detail, null);
	}

	public ApiException(ErrorCode code, String detail, Long retryAfterSeconds) {
		super(detail);
		this.code = code;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public ErrorCode code() {
		return code;
	}

	public Long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
