package medi_scan.backend.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gives every request an id, returns it as {@code X-Request-Id}, and puts it in the logging
 * MDC so a user-visible error can be traced to the log lines that produced it.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} and <em>outside</em> the Spring Security
 * chain on purpose: a 401 produced by the authentication entry point has to carry the header
 * too, and that happens before any controller runs.
 *
 * <p>The header is set before the chain proceeds, because a response that has already been
 * committed (a streamed SSE body, for instance) will not accept new headers afterwards.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";

	/**
	 * The current request's id, or a placeholder when called outside a request (a scheduled
	 * job, say). Never returns null, so the {@code requestId} field is always populated.
	 */
	public static String currentRequestId() {
		String id = MDC.get(MDC_KEY);
		return id != null ? id : "no-request";
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		// Generated server-side, never read from the request: a client-supplied id would let
		// a caller forge or collide with log entries.
		String requestId = UUID.randomUUID().toString();
		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			// Servlet threads are pooled, so a leaked MDC entry would reappear on an
			// unrelated later request.
			MDC.remove(MDC_KEY);
		}
	}
}
