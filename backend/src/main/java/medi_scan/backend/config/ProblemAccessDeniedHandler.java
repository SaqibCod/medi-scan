package medi_scan.backend.config;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders a 403 from the security filter chain as Problem Details, so an authenticated caller
 * missing a role gets {@code FORBIDDEN} with a {@code requestId} rather than an empty body.
 *
 * <p>403 means "authenticated, but not allowed". A caller asking for someone else's
 * <em>report</em> gets 404 {@code REPORT_NOT_FOUND} instead, and never reaches this class:
 * confirming that an id exists would itself leak something (contract section 4.2).
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public ProblemAccessDeniedHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {

		ProblemDetail problem = ProblemDetails.of(ErrorCode.FORBIDDEN,
				"You do not have permission to use this endpoint.");

		response.setStatus(ErrorCode.FORBIDDEN.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getOutputStream(), problem);
	}
}
