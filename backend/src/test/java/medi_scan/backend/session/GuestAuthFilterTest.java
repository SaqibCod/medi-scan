package medi_scan.backend.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import medi_scan.backend.config.RequestIdFilter;
import medi_scan.backend.support.PostgresTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The guest authentication filter and the {@code 401 SESSION_INVALID} contract on report
 * routes (contract section 2.2, {@code docs/dataflow.md} section 3.6).
 */
class GuestAuthFilterTest extends PostgresTestBase {

	private static final String REPORT_ROUTE = "/api/reports/whoami";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	SessionService sessionService;

	@Autowired
	SessionRepository sessionRepository;

	@Autowired
	ObjectMapper objectMapper;

	@Test
	@DisplayName("accepts a valid token and exposes the session as the owner")
	void acceptsValidToken() throws Exception {
		SessionResponse session = sessionService.create();

		MvcResult result = mockMvc.perform(get(REPORT_ROUTE)
				.header(GuestAuthFilter.HEADER, session.token()))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);

		JsonNode owner = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(owner.get("ownerType").asString()).isEqualTo("GUEST");
		assertThat(owner.get("ownerId").asString())
				.isEqualTo(sessionService.findActive(session.token()).orElseThrow().getId().toString());
	}

	@Test
	@DisplayName("rejects a missing token with 401 SESSION_INVALID")
	void rejectsMissingToken() throws Exception {
		assertSessionInvalid(mockMvc.perform(get(REPORT_ROUTE)).andReturn());
	}

	@Test
	@DisplayName("rejects an unknown token with 401 SESSION_INVALID")
	void rejectsUnknownToken() throws Exception {
		// Well-formed but never issued, so it hashes to something not in the table.
		String unknown = "x".repeat(43);

		assertSessionInvalid(mockMvc.perform(get(REPORT_ROUTE)
				.header(GuestAuthFilter.HEADER, unknown))
				.andReturn());
	}

	@Test
	@DisplayName("rejects an expired token with 401 SESSION_INVALID")
	void rejectsExpiredToken() throws Exception {
		// Written directly, because the service can only create sessions that are valid.
		// The hash is of the literal token below.
		String token = "expired-session-token-used-only-in-this-test";
		Instant now = Instant.now();
		sessionRepository.save(new Session(
				UUID.randomUUID(),
				sha256Hex(token),
				now.minus(48, ChronoUnit.HOURS),
				now.minus(1, ChronoUnit.HOURS)));

		assertSessionInvalid(mockMvc.perform(get(REPORT_ROUTE)
				.header(GuestAuthFilter.HEADER, token))
				.andReturn());
	}

	@Test
	@DisplayName("rejects a blank token with 401 SESSION_INVALID")
	void rejectsBlankToken() throws Exception {
		assertSessionInvalid(mockMvc.perform(get(REPORT_ROUTE)
				.header(GuestAuthFilter.HEADER, "   "))
				.andReturn());
	}

	@Test
	@DisplayName("an invalid bearer token never falls back to a valid guest token")
	void bearerNeverFallsBackToGuest() throws Exception {
		// CLAUDE.md rule 16. Both credentials are sent and the session token is genuinely
		// valid, yet the request must still fail, because the bearer token wins and is bad.
		SessionResponse session = sessionService.create();

		MvcResult result = mockMvc.perform(get(REPORT_ROUTE)
				.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
				.header(GuestAuthFilter.HEADER, session.token()))
				.andReturn();

		assertThat(result.getResponse().getStatus())
				.as("a bad bearer token must not degrade to guest access")
				.isEqualTo(401);
	}

	/** Every rejection must be Problem Details with the contract's code and a request id. */
	private void assertSessionInvalid(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(result.getResponse().getContentType())
				.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

		JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(problem.get("code").asString()).isEqualTo("SESSION_INVALID");
		assertThat(problem.get("status").asInt()).isEqualTo(401);
		assertThat(problem.get("requestId").asString()).isNotBlank();

		assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotBlank();
	}

	private static String sha256Hex(String value) throws Exception {
		java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
		return java.util.HexFormat.of()
				.formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
}
