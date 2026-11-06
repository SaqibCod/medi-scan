package dev.saq.mediscan.config;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders a 401 from the security filter chain as Problem Details.
 *
 * <p>Needed because rejections inside the filter chain happen before any controller is
 * selected, so {@link ApiExceptionHandler} never sees them. Without this, Spring Security
 * would return an empty body and the client would have no {@code code} to switch on.
 *
 * <p>Which code depends on the route, per {@code docs/dataflow.md} section 3.6:
 *
 * <ul>
 * <li>{@code /api/reports/**} - {@code SESSION_INVALID}. The caller needs <em>a</em>
 * credential, guest or user, and the client's move is to create a new session and drop the
 * report ids it was holding.</li>
 * <li>Signed-in-only routes - {@code AUTH_REQUIRED}. A guest token would not help; the user
 * has to sign in. These routes arrive in phase 6, but the mapping lives here so adding them
 * does not mean revisiting this class.</li>
 * </ul>
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	public ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		ErrorCode code = codeFor(request.getRequestURI());
		String detail = code == ErrorCode.SESSION_INVALID
				? "No valid session. Create a new session and try again."
				: "Sign in to use this endpoint.";

		ProblemDetail problem = ProblemDetails.of(code, detail);

		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getOutputStream(), problem);
	}

	private ErrorCode codeFor(String path) {
		return path != null && path.startsWith("/api/reports")
				? ErrorCode.SESSION_INVALID
				: ErrorCode.AUTH_REQUIRED;
	}
}
