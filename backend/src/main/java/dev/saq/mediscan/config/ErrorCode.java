package dev.saq.mediscan.config;

import org.springframework.http.HttpStatus;

/**
 * Every HTTP error code in {@code docs/api-contract.md} section 8.1.
 *
 * <p>{@code code} is the stable field clients switch on; {@code title} and the per-response
 * {@code detail} are for humans and may be reworded freely. Adding a code here without adding
 * it to the contract first is a contract violation, not a convenience.
 *
 * <p>Report <em>processing</em> failure codes ({@code UNREADABLE}, {@code NO_RESULTS_FOUND},
 * and so on, contract section 8.2) are a different set: they are stored on the report and
 * returned inside a 200 response body, so they do not belong in this enum.
 */
public enum ErrorCode {

	/** Request body or parameters are invalid. Carries an {@code errors} array. */
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Invalid request"),

	/** File content does not match its declared type. */
	FILE_SIGNATURE_MISMATCH(HttpStatus.BAD_REQUEST, "File signature mismatch"),

	/**
	 * Report endpoint called with no bearer token and a missing, unknown or expired session
	 * token. The client should create a new session and drop any report ids it held.
	 */
	SESSION_INVALID(HttpStatus.UNAUTHORIZED, "Session invalid"),

	/** Signed-in-only endpoint called without a bearer token. */
	AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required"),

	/** Access token has expired. The client refreshes once and retries once. */
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Access token expired"),

	/**
	 * Access token is malformed, has a bad signature or an unknown {@code kid}, or the wrong
	 * issuer or audience. Also covers a key rotated out of {@code JWT_SIGNING_KEYS}, which is
	 * why the client refreshes once on this too.
	 */
	TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Access token invalid"),

	/** Google ID token failed verification. */
	GOOGLE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Google token invalid"),

	/** Refresh token unknown, expired, revoked or reused. The user must sign in again. */
	REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Refresh token invalid"),

	/** Signed in, but missing the required role. */
	FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),

	/**
	 * Report does not exist <em>for this caller</em>. Another owner's report returns this and
	 * not 403, so the API never confirms that an id exists.
	 */
	REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "Report not found"),

	/** Unknown sample id. */
	SAMPLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Sample not found"),

	/** Chat requested before the report reached {@code DONE}. */
	REPORT_NOT_READY(HttpStatus.CONFLICT, "Report not ready"),

	/** File over 10 MB. Still HTTP 413; Spring 7 renamed the constant. */
	FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "File too large"),

	/** Not a PDF, PNG or JPEG. */
	UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported file type"),

	/** Per-IP or per-user limit reached. Always sent with {@code Retry-After}. */
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit reached"),

	/** Daily LLM cap reached. {@code Retry-After} is the seconds until midnight UTC. */
	CAPACITY(HttpStatus.TOO_MANY_REQUESTS, "Daily capacity reached"),

	/** Processing queue full. No report is created. */
	BUSY(HttpStatus.TOO_MANY_REQUESTS, "Server busy"),

	/** Unexpected server error. The response quotes a {@code requestId}. */
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

	private static final String TYPE_PREFIX = "https://medi-scan.dev/errors/";

	private final HttpStatus status;
	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

	/**
	 * The Problem Details {@code type} URI, for example
	 * {@code https://medi-scan.dev/errors/file-too-large}.
	 */
	public String type() {
		return TYPE_PREFIX + name().toLowerCase().replace('_', '-');
	}
}
