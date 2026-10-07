package dev.saq.mediscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API against {@code docs/api-contract.md} ({@code LLD} 17.4).
 *
 * <p>Real responses are serialized and their field names compared with the documented
 * examples, so the contract is checked rather than described. The failure this catches is the
 * one that silently breaks the client: a field renamed on the server, or added to the server
 * and never written down.
 *
 * <p>It also reads the contract file itself, so the enums and codes in the document and in the
 * code cannot drift apart. {@code CLAUDE.md} requires the contract and the code to change
 * together; this is what makes that a build failure rather than a convention.
 */
class ApiContractTest extends PostgresTestBase {

	private static final Path CONTRACT = Paths.get("..", "docs", "api-contract.md");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ReportFixtures fixtures;

	private String token;
	private UUID sessionId;

	@BeforeEach
	void freshSession() {
		fixtures.clear();
		token = fixtures.sessionToken();
		sessionId = fixtures.sessionIdFor(token);
	}

	// --- response shapes -----------------------------------------------------

	@Test
	@DisplayName("POST /api/reports 202 has exactly the documented fields")
	void createResponseFields() throws Exception {
		String body = mockMvc.perform(post("/api/reports")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sampleId": "lipid-panel", "consent": true}"""))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		// Contract section 4.1. Exactly these - an extra field is an undocumented API change.
		assertThat(fieldsOf(body))
				.containsExactlyInAnyOrder("id", "status", "sourceType", "createdAt", "expiresAt");
	}

	@Test
	@DisplayName("GET /api/reports/{id} has exactly the documented fields")
	void reportResponseFields() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);

		String body = fetch(reportId);

		// Contract section 4.2: error and result are present and null while processing.
		assertThat(fieldsOf(body)).containsExactlyInAnyOrder(
				"id", "status", "sourceType", "createdAt", "expiresAt", "error", "result");
	}

	@Test
	@DisplayName("GET /api/samples has exactly the documented fields")
	void sampleResponseFields() throws Exception {
		String body = mockMvc.perform(get("/api/samples"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		JsonNode first = JSON.readTree(body).get(0);

		// Contract section 3. No report text, which is the point of the separate DTO.
		assertThat(fieldsOf(first))
				.containsExactlyInAnyOrder("id", "title", "description", "markerCount");
	}

	@Test
	@DisplayName("an error response has exactly the documented Problem Details fields")
	void errorResponseFields() throws Exception {
		String body = mockMvc.perform(get("/api/reports/" + UUID.randomUUID())
				.header("X-Session-Token", token))
				.andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();

		// Contract section 1.5: RFC 9457 plus the two fields Medi-Scan adds.
		assertThat(fieldsOf(body))
				.contains("type", "title", "status", "detail", "code", "requestId");
	}

	@Test
	@DisplayName("a validation error carries the documented errors array")
	void validationErrorFields() throws Exception {
		String body = mockMvc.perform(post("/api/reports")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sampleId": "lipid-panel"}"""))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();

		assertThat(fieldsOf(body)).contains("code", "requestId", "errors");

		JsonNode firstError = JSON.readTree(body).get("errors").get(0);
		assertThat(fieldsOf(firstError)).containsExactlyInAnyOrder("field", "message");
	}

	// --- the contract document agrees with the code --------------------------

	@Test
	@DisplayName("every HTTP error code in the enum is documented in section 8.1")
	void httpErrorCodesAreDocumented() throws Exception {
		String contract = readContract();

		for (ErrorCode code : ErrorCode.values()) {
			assertThat(contract)
					.as("%s must be documented in docs/api-contract.md section 8.1", code)
					.contains("`" + code.name() + "`");
		}
	}

	@Test
	@DisplayName("every report failure code is documented in section 8.2, with its message")
	void reportErrorCodesAreDocumented() throws Exception {
		String contract = readContract();

		for (ReportErrorCode code : ReportErrorCode.values()) {
			assertThat(contract)
					.as("%s must be documented in docs/api-contract.md section 8.2", code)
					.contains("`" + code.name() + "`");

			// The suggested UI message is part of the contract, and the server sends it.
			assertThat(contract)
					.as("the suggested message for %s must match the contract", code)
					.contains(code.message());
		}
	}

	@Test
	@DisplayName("the contract documents no code the enums do not define")
	void contractDefinesNoUnknownCodes() throws Exception {
		String contract = readContract();

		Set<String> known = new java.util.HashSet<>();
		for (ErrorCode code : ErrorCode.values()) {
			known.add(code.name());
		}
		for (ReportErrorCode code : ReportErrorCode.values()) {
			known.add(code.name());
		}

		// Scans the two code tables for SCREAMING_SNAKE identifiers in backticks. A code in
		// the document with no enum behind it is a promise the server does not keep.
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("\\|\\s*`([A-Z][A-Z_]{3,})`\\s*\\|")
				.matcher(section(contract, "## 8. Error codes", "## 9."));

		while (matcher.find()) {
			String documented = matcher.group(1);
			assertThat(known)
					.as("%s is documented but no enum defines it", documented)
					.contains(documented);
		}
	}

	@Test
	@DisplayName("the status enum matches the contract's ReportStatus union")
	void reportStatusesMatchContract() throws Exception {
		String contract = readContract();

		// The client's TypeScript union is generated from this line, so a new status that was
		// not added here would arrive at a client that cannot represent it.
		assertThat(contract).contains(
				"\"PENDING\" | \"PROCESSING\" | \"DONE\" | \"FAILED\"");

		assertThat(List.of(dev.saq.mediscan.report.ReportStatus.values()))
				.extracting(Enum::name)
				.containsExactly("PENDING", "PROCESSING", "DONE", "FAILED");
	}

	@Test
	@DisplayName("the contract version was bumped for the 2.2 changes")
	void contractVersionIsCurrent() throws Exception {
		String contract = readContract();

		// Phase 2 added DOCUMENT_TOO_LONG and the image restriction, both of which are
		// changelog entries under 2.2.
		assertThat(contract).contains("**Version:** 2.2");
		assertThat(section(contract, "## 11. Changelog", "**2.1**"))
				.contains("DOCUMENT_TOO_LONG");
	}

	// --- helpers -------------------------------------------------------------

	private String fetch(UUID reportId) throws Exception {
		return mockMvc.perform(get("/api/reports/" + reportId)
				.header("X-Session-Token", token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private static List<String> fieldsOf(String json) {
		return fieldsOf(JSON.readTree(json));
	}

	private static List<String> fieldsOf(JsonNode node) {
		return List.copyOf(node.propertyNames());
	}

	private static String readContract() throws Exception {
		assertThat(CONTRACT).as("the API contract must exist").exists();
		return Files.readString(CONTRACT);
	}

	/** The slice of the document between two markers, so a scan stays in its section. */
	private static String section(String document, String from, String to) {
		int start = document.indexOf(from);
		assertThat(start).as("contract should contain '%s'", from).isNotNegative();

		int end = document.indexOf(to, start + from.length());
		return end < 0 ? document.substring(start) : document.substring(start, end);
	}
}
