package dev.saq.mediscan.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import dev.saq.mediscan.support.PostgresTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The invariants of the error contract (contract section 1.5): every error response is
 * {@code application/problem+json}, carries a stable {@code code} and a {@code requestId} in
 * the body, and repeats that id in the {@code X-Request-Id} header.
 *
 * <p>These are asserted across several different failure paths on purpose. The body for a
 * controller exception, a security rejection and a Spring MVC error are produced by three
 * different pieces of code, and the point of the contract is that a client cannot tell them
 * apart.
 */
class ErrorContractTest extends PostgresTestBase {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Test
	@DisplayName("a security rejection is Problem Details with a code and request id")
	void securityRejectionShape() throws Exception {
		assertProblemDetails(get("/api/reports/00000000-0000-0000-0000-000000000000"), 401);
	}

	@Test
	@DisplayName("every unauthenticated route shape is consistent across methods")
	void consistentAcrossMethods() throws Exception {
		List<RequestBuilder> requests = List.of(
				get("/api/reports/00000000-0000-0000-0000-000000000000"),
				delete("/api/reports/00000000-0000-0000-0000-000000000000"),
				post("/api/reports"));

		for (RequestBuilder request : requests) {
			assertProblemDetails(request, 401);
		}
	}

	@Test
	@DisplayName("a wrong method on a real route still produces a coded Problem Details body")
	void springMvcErrorShape() throws Exception {
		// POST is the only method on /api/sessions, so GET exercises the
		// ResponseEntityExceptionHandler path rather than a thrown ApiException.
		MvcResult result = mockMvc.perform(get("/api/sessions")).andReturn();

		assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
		assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotBlank();

		JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(problem.get("code").asString()).isNotBlank();
		assertThat(problem.get("requestId").asString()).isNotBlank();
	}

	@Test
	@DisplayName("the body's requestId matches the X-Request-Id header")
	void requestIdMatchesHeader() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/reports/whoami")).andReturn();

		String headerId = result.getResponse().getHeader(RequestIdFilter.HEADER);
		JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());

		// The whole point of the id: a user quotes what they saw, and it is greppable in the
		// logs for that exact request.
		assertThat(problem.get("requestId").asString()).isEqualTo(headerId);
	}

	@Test
	@DisplayName("each response gets its own request id")
	void requestIdsAreUnique() throws Exception {
		String first = mockMvc.perform(get("/api/reports/whoami"))
				.andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
		String second = mockMvc.perform(get("/api/reports/whoami"))
				.andReturn().getResponse().getHeader(RequestIdFilter.HEADER);

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	@DisplayName("error codes come from the contract's enum, never a free-form string")
	void codeIsFromTheEnum() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/reports/whoami")).andReturn();

		JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
		String code = problem.get("code").asString();

		assertThat(ErrorCode.valueOf(code)).isEqualTo(ErrorCode.SESSION_INVALID);
	}

	private void assertProblemDetails(RequestBuilder request, int expectedStatus) throws Exception {
		MvcResult result = mockMvc.perform(request).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
		assertThat(result.getResponse().getContentType())
				.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotBlank();

		JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(problem.get("code").asString()).isNotBlank();
		assertThat(problem.get("requestId").asString()).isNotBlank();
		assertThat(problem.get("title").asString()).isNotBlank();
		assertThat(problem.get("type").asString()).startsWith("https://medi-scan.dev/errors/");
		assertThat(problem.get("status").asInt()).isEqualTo(expectedStatus);
	}
}
