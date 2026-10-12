package dev.saq.mediscan.auth;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.config.ProblemDetails;
import dev.saq.mediscan.ratelimit.IpRateLimiter;
import dev.saq.mediscan.ratelimit.RateLimitExceededException;
import dev.saq.mediscan.stats.StatsRecorder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * Ten sign-in and ten refresh attempts per minute per IP (contract section 1.6).
 *
 * <p>Separate buckets for the two endpoints, so a burst of auto sign-ins in new tabs cannot
 * use up the allowance a reload needs for its refresh. Ten a minute is generous for real
 * behaviour - one page load does at most one of each - and still caps what a script can try.
 *
 * <p>Keyed by IP rather than by user, necessarily: nobody is authenticated yet at either
 * endpoint. That is also why these two need a limit at all, while the rest of the API can rely
 * on an owner.
 *
 * <p>Logout is deliberately unlimited. It takes a token the caller already holds, revokes
 * only that token's family, and a client that cannot log out is stuck showing a signed-in
 * header - so rate limiting it would protect nothing and break something.
 *
 * <p>Runs before the bearer filter: these requests carry no {@code Authorization} header, and
 * refusing them early means a flood never reaches token verification or the database.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

	static final String SIGN_IN_PATH = "/api/auth/google";
	static final String REFRESH_PATH = "/api/auth/refresh";

	private final IpRateLimiter rateLimiter;
	private final JsonMapper jsonMapper;
	private final StatsRecorder stats;

	public AuthRateLimitFilter(IpRateLimiter rateLimiter, JsonMapper jsonMapper,
			StatsRecorder stats) {

		this.rateLimiter = rateLimiter;
		this.jsonMapper = jsonMapper;
		this.stats = stats;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!HttpMethod.POST.matches(request.getMethod())) {
			return true;
		}
		String path = request.getRequestURI();
		return !SIGN_IN_PATH.equals(path) && !REFRESH_PATH.equals(path);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		boolean signIn = SIGN_IN_PATH.equals(request.getRequestURI());
		try {
			if (signIn) {
				rateLimiter.checkGoogleSignIn(request);
			}
			else {
				rateLimiter.checkTokenRefresh(request);
			}
		}
		catch (RateLimitExceededException ex) {
			// Counted so the dashboard can distinguish a quiet day from being hammered.
			stats.recordRateLimitRejection();
			writeRateLimited(response, ex.retryAfterSeconds());
			return;
		}

		chain.doFilter(request, response);
	}

	private void writeRateLimited(HttpServletResponse response, long retryAfterSeconds)
			throws IOException {

		// No detail about which endpoint or how many attempts remain: this response goes to
		// whoever is probing as readily as to a real client.
		ProblemDetail problem = ProblemDetails.of(ErrorCode.RATE_LIMITED,
				"Too many attempts from this address. Try again shortly.");

		response.setStatus(ErrorCode.RATE_LIMITED.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		// Contract section 1.6: a 429 always says how long to wait.
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
		jsonMapper.writeValue(response.getOutputStream(), problem);
	}
}
