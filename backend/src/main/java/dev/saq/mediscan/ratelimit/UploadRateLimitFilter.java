package dev.saq.mediscan.ratelimit;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.config.ProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enforces the per-IP upload limit on {@code POST /api/reports}.
 *
 * <p>A filter rather than a check inside the controller, because the limit has to apply before
 * the request body is read. A multipart request body is up to 10 MB; letting Spring parse and
 * spool that to disk before deciding to reject it would mean a client could spend the server's
 * disk and bandwidth at will, which is most of what the limit is for.
 *
 * <p>Renders its own response for the same reason: a filter's exception never reaches the
 * {@code @RestControllerAdvice}, and a 429 without a {@code code} and a {@code Retry-After}
 * would break the contract the client switches on.
 *
 * <p>Not a {@code @Component}. It is placed explicitly in the security chain by
 * {@code SecurityConfig}, after the guest filter, so that the ordering {@code LLD} 6.1
 * specifies - authenticate, then rate limit - is visible where the rest of the chain is
 * written, rather than depending on bean registration order.
 */
public class UploadRateLimitFilter extends OncePerRequestFilter {

	private static final String PATH = "/api/reports";

	private final IpRateLimiter rateLimiter;
	private final JsonMapper jsonMapper;

	public UploadRateLimitFilter(IpRateLimiter rateLimiter, JsonMapper jsonMapper) {
		this.rateLimiter = rateLimiter;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Only {@code POST /api/reports}.
	 *
	 * <p>An exact path match, so {@code POST /api/reports/{id}/chat} in phase 5 gets its own
	 * limit rather than silently inheriting this one.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !(HttpMethod.POST.matches(request.getMethod()) && PATH.equals(request.getRequestURI()));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		try {
			rateLimiter.checkUpload(request);
		}
		catch (RateLimitExceededException ex) {
			writeRateLimited(response, ex.retryAfterSeconds());
			return;
		}
		chain.doFilter(request, response);
	}

	private void writeRateLimited(HttpServletResponse response, long retryAfterSeconds)
			throws IOException {

		ProblemDetail problem = ProblemDetails.of(ErrorCode.RATE_LIMITED,
				"Too many uploads from this address. Try again shortly.");

		response.setStatus(ErrorCode.RATE_LIMITED.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		// Contract section 1.6: a 429 always says how long to wait.
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
		jsonMapper.writeValue(response.getOutputStream(), problem);
	}
}
