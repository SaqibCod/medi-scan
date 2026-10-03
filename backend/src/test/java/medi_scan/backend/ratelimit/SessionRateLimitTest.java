package medi_scan.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import medi_scan.backend.session.SessionRepository;
import medi_scan.backend.support.PostgresTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The per-IP session creation limit (contract section 1.6).
 *
 * <p>The limit is lowered to 3 for this class so the test stays fast and readable. That gives
 * this class its own application context, which is why {@code PostgresTestBase} keeps a single
 * container outside the context lifecycle - the extra context reuses the same database rather
 * than starting a second one.
 */
@TestPropertySource(properties = "mediscan.ratelimit.sessions-per-hour=3")
class SessionRateLimitTest extends PostgresTestBase {

	private static final int LIMIT = 3;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	SessionRepository sessionRepository;

	@Test
	@DisplayName("allows the limit, then returns 429 RATE_LIMITED with Retry-After")
	void blocksOverTheLimit() throws Exception {
		for (int i = 0; i < LIMIT; i++) {
			assertThat(mockMvc.perform(post("/api/sessions")).andReturn().getResponse().getStatus())
					.as("request %d of the allowance", i + 1)
					.isEqualTo(201);
		}

		MvcResult blocked = mockMvc.perform(post("/api/sessions")).andReturn();

		assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
		assertThat(blocked.getResponse().getContentType())
				.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

		JsonNode problem = objectMapper.readTree(blocked.getResponse().getContentAsString());
		assertThat(problem.get("code").asString()).isEqualTo("RATE_LIMITED");
		assertThat(problem.get("requestId").asString()).isNotBlank();

		// Contract section 1.6: a 429 always says how long to wait, and never says zero.
		String retryAfter = blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
		assertThat(retryAfter).isNotNull();
		assertThat(Long.parseLong(retryAfter)).isPositive();
	}

	@Test
	@DisplayName("a blocked request creates no session row")
	void blockedRequestWritesNothing() throws Exception {
		// The limit is checked before the session is created, so hitting it must not leave
		// rows behind. Shares the bucket with the test above, so by this point it may already
		// be empty - which is exactly the state being checked.
		for (int i = 0; i < LIMIT + 1; i++) {
			mockMvc.perform(post("/api/sessions"));
		}

		long before = sessionRepository.count();
		MvcResult blocked = mockMvc.perform(post("/api/sessions")).andReturn();

		assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
		assertThat(sessionRepository.count()).isEqualTo(before);
	}
}
