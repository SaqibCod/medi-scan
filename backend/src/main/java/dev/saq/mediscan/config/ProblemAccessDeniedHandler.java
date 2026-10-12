package dev.saq.mediscan.config;

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
 * Renders an authorization failure from the security filter chain as Problem Details.
 *
 * <p>Two different answers, because "not allowed" means two different things here:
 *
 * <ul>
 * <li><strong>A guest on a signed-in-only route</strong> - {@code 401 AUTH_REQUIRED}. A guest
 * is technically authenticated, so Spring calls this handler rather than the entry point, but
 * the honest answer is "sign in", not "forbidden": the caller has no account yet and 403 would
 * suggest their credential was rejected (LLD 6.4).</li>
 * <li><strong>A signed-in user without {@code ADMIN}</strong> - {@code 403 FORBIDDEN}. They
 * are who they say they are and still may not do this.</li>
 * </ul>
 *
 * <p>403 never applies to <em>reports</em>. A caller asking for someone else's report gets 404
 * {@code REPORT_NOT_FOUND} and never reaches this class: confirming that an id exists would
 * itself leak something (contract section 4.2).
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

		ErrorCode code = DeniedAccess.codeForCurrentCaller();
		ProblemDetail problem = ProblemDetails.of(code, DeniedAccess.detailFor(code));

		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getOutputStream(), problem);
	}
}
