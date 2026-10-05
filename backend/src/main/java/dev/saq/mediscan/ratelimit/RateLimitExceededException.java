package dev.saq.mediscan.ratelimit;

import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.ErrorCode;

/**
 * A per-IP or per-user limit has been reached: {@code 429 RATE_LIMITED} with
 * {@code Retry-After} (contract section 1.6).
 *
 * <p>Extends {@link ApiException} so the one exception handler renders it; nothing extra is
 * needed for the status, the {@code code}, or the header.
 */
public class RateLimitExceededException extends ApiException {

	public RateLimitExceededException(long retryAfterSeconds) {
		super(ErrorCode.RATE_LIMITED, "Too many requests. Try again shortly.", retryAfterSeconds);
	}
}
