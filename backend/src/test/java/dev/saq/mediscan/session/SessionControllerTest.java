package dev.saq.mediscan.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import dev.saq.mediscan.config.RequestIdFilter;
import dev.saq.mediscan.support.PostgresTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /api/sessions} against contract section 2.2.
 */
class SessionControllerTest extends PostgresTestBase {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	SessionRepository sessionRepository;

	@Autowired
	ObjectMapper objectMapper;

	@Test
	@DisplayName("returns 201 with a 43-character base64url token and a 24-hour expiry")
	void createsSession() throws Exception {
		Instant before = Instant.now();

		MvcResult result = mockMvc.perform(post("/api/sessions")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);

		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

		// Contract section 2.2: 32 random bytes, base64url encoded, 43 characters.
		String token = body.get("token").asString();
		assertThat(token).hasSize(43);
		assertThat(token).matches("[A-Za-z0-9_-]{43}");
		assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);

		Instant expiresAt = Instant.parse(body.get("expiresAt").asString());
		assertThat(expiresAt).isCloseTo(before.plus(24, ChronoUnit.HOURS), within(5, ChronoUnit.MINUTES));
	}

	@Test
	@DisplayName("two sessions never get the same token")
	void tokensAreUnique() throws Exception {
		String first = tokenFromNewSession();
		String second = tokenFromNewSession();

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	@DisplayName("stores only the token's SHA-256 hash, never the token")
	void storesHashNotToken() throws Exception {
		String token = tokenFromNewSession();

		List<String> storedHashes = sessionRepository.findAll().stream()
				.map(Session::getTokenHash)
				.toList();

		// The point of the test: the raw token must appear nowhere in the table.
		assertThat(storedHashes).isNotEmpty();
		assertThat(storedHashes).doesNotContain(token);
		assertThat(storedHashes).contains(sha256Hex(token));
		assertThat(storedHashes).allSatisfy(hash -> assertThat(hash).hasSize(64).matches("[0-9a-f]{64}"));
	}

	@Test
	@DisplayName("a freshly issued token authenticates its owner as a guest")
	void issuedTokenAuthenticates() throws Exception {
		String token = tokenFromNewSession();

		MvcResult result = mockMvc.perform(get("/api/reports/support/whoami")
				.header(GuestAuthFilter.HEADER, token))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);

		JsonNode owner = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(owner.get("ownerType").asString()).isEqualTo("GUEST");
		assertThat(owner.get("ownerId").asString()).isNotBlank();
	}

	@Test
	@DisplayName("a success response also carries X-Request-Id")
	void setsRequestIdHeader() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/sessions")).andReturn();

		assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotBlank();
	}

	private String tokenFromNewSession() throws Exception {
		String body = mockMvc.perform(post("/api/sessions"))
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("token").asString();
	}

	private static String sha256Hex(String value) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
	}
}
