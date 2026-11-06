package dev.saq.mediscan.config;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.auth.ExpiryValidator;

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
 * <p>Which code depends on what failed, per {@code docs/dataflow.md} section 3.6:
 *
 * <ul>
 * <li><strong>A bearer token was sent and rejected</strong> - {@code TOKEN_EXPIRED} if it had
 * simply run out, {@code TOKEN_INVALID} for anything else. The client refreshes once and
 * retries on either, but the distinction tells it - and whoever reads the logs - whether this
 * was the ordinary fifteen-minute expiry or something wrong with the token.</li>
 * <li>{@code /api/reports/**} with no credential at all - {@code SESSION_INVALID}. The caller
 * needs <em>a</em> credential, guest or user, and the client's move is to create a new session
 * and drop the report ids it was holding.</li>
 * <li>Signed-in-only routes - {@code AUTH_REQUIRED}. A guest token would not help; the user
 * has to sign in.</li>
 * </ul>
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

	/**
	 * Exactly what contract section 2.1 promises, and nothing more.
	 *
	 * <p>Spring's own {@code BearerTokenAuthenticationEntryPoint} appends
	 * {@code error_description} and {@code resource_metadata}. The description is derived from
	 * the validation failure, and this API does not put that in a response header.
	 */
	private static final String BEARER_CHALLENGE = "Bearer error=\"invalid_token\"";

	private final ObjectMapper objectMapper;

	public ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		boolean bearerFailure = authException instanceof OAuth2AuthenticationException;
		ErrorCode code = bearerFailure
				? (isExpired(authException) ? ErrorCode.TOKEN_EXPIRED : ErrorCode.TOKEN_INVALID)
				: codeFor(request.getRequestURI());

		if (bearerFailure) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
		}

		ProblemDetail problem = ProblemDetails.of(code, detailFor(code));

		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getOutputStream(), problem);
	}

	/**
	 * Whether this failure was an expired access token.
	 *
	 * <p>Read from the <strong>cause</strong>, and by error code. The exception that reaches
	 * here is an {@code InvalidBearerTokenException} whose own error is hardcoded to
	 * {@code invalid_token}; the custom {@code token_expired} code survives only in the
	 * {@link JwtValidationException} it wraps (LLD 6.3, D4).
	 *
	 * <p>Never matched on the description text: descriptions are human-facing and change.
	 */
	private static boolean isExpired(AuthenticationException authException) {
		if (!(authException.getCause() instanceof JwtValidationException validation)) {
			return false;
		}
		for (OAuth2Error error : validation.getErrors()) {
			if (ExpiryValidator.TOKEN_EXPIRED.equals(error.getErrorCode())) {
				return true;
			}
		}
		return false;
	}

	private static ErrorCode codeFor(String path) {
		return path != null && path.startsWith("/api/reports")
				? ErrorCode.SESSION_INVALID
				: ErrorCode.AUTH_REQUIRED;
	}

	private static String detailFor(ErrorCode code) {
		return switch (code) {
			case TOKEN_EXPIRED -> "Your session has expired. Refresh and try again.";
			case TOKEN_INVALID -> "That credential is not valid. Refresh and try again.";
			case SESSION_INVALID -> "No valid session. Create a new session and try again.";
			default -> "Sign in to use this endpoint.";
		};
	}
}
