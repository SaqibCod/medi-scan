package dev.saq.mediscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.analysis.ModelExtraction;
import dev.saq.mediscan.analysis.ModelSummary;
import dev.saq.mediscan.llm.FakeLlmProvider;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.llm.LlmRequest;
import dev.saq.mediscan.support.LogCapture;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestPdfs;

/**
 * The canary test ({@code LLD} 17.3).
 *
 * <p>Runs a report containing unique, unmistakable fake personal data through the entire
 * pipeline over HTTP, then goes looking for those strings in every place they must not be:
 *
 * <ul>
 * <li>any log line, at any level, including exception messages;</li>
 * <li>any prompt the model received;</li>
 * <li>any text column in any database table;</li>
 * <li>any response body.</li>
 * </ul>
 *
 * <p>The strings are deliberately bizarre. A test looking for "Jane" could pass because
 * "Jane" happens not to appear while a real name would have; {@code ZZCANARY-Name-7391}
 * appears if and only if this report's own data leaked.
 *
 * <p>This is the single most load-bearing test in the suite. The privacy rules in
 * {@code CLAUDE.md} are mostly about things <em>not</em> happening, and the only way to hold
 * such a rule over time is to look.
 */
class CanaryTest extends PostgresTestBase {

	private static final String CANARY_NAME = "ZZCANARY-Name-7391";
	private static final String CANARY_ID = "ZZCANARY-ID-5520";
	private static final String CANARY_PHONE = "(555) 555-0177";
	private static final String CANARY_DOB = "04/12/1985";

	private static final List<String> CANARIES =
			List.of(CANARY_NAME, CANARY_ID, CANARY_PHONE, CANARY_DOB);

	/** A report whose header is nothing but canaries. */
	private static final List<String> CANARY_REPORT = List.of(
			"NORTHSIDE COMMUNITY LABORATORY",
			"Patient Name: " + CANARY_NAME,
			"Patient ID: " + CANARY_ID,
			"Phone: " + CANARY_PHONE,
			"DOB: " + CANARY_DOB,
			"Collected: 09/28/2026",
			"",
			"Total Cholesterol           238         mg/dL       <200           H",
			"HDL Cholesterol             38          mg/dL       >40            L",
			"Triglycerides               140         mg/dL       <150");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	FakeLlmProvider provider;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	JdbcClient jdbc;

	private String token;

	@BeforeEach
	void reset() {
		fixtures.clear();
		provider.reset();
		jdbc.sql("delete from llm_usage").update();
		token = fixtures.sessionToken();

		scriptModel();
	}

	@Test
	@DisplayName("pasted text: no canary reaches a log, a prompt, the database or a response")
	void pastedTextLeaksNothing() throws Exception {
		try (LogCapture logs = LogCapture.start()) {
			UUID reportId = postText();
			String responseBody = awaitTerminal(reportId);

			assertNoCanaryIn("the captured log", logs.allText());
			assertNoCanaryInPrompts();
			assertNoCanaryInDatabase();
			assertNoCanaryIn("the response body", responseBody);

			// Proves the test actually ran something, rather than passing on an empty log.
			assertThat(logs.lineCount()).isPositive();
		}
	}

	@Test
	@DisplayName("PDF upload: no canary reaches a log, a prompt, the database or a response")
	void pdfUploadLeaksNothing() throws Exception {
		try (LogCapture logs = LogCapture.start()) {
			UUID reportId = postPdf();
			String responseBody = awaitTerminal(reportId);

			// The PDF path is the one with a temp file and a filename, so it has two extra
			// ways to leak that the pasted path does not.
			assertNoCanaryIn("the captured log", logs.allText());
			assertNoCanaryInPrompts();
			assertNoCanaryInDatabase();
			assertNoCanaryIn("the response body", responseBody);
		}
	}

	@Test
	@DisplayName("a failed report leaks nothing either")
	void failedReportLeaksNothing() throws Exception {
		// The failure path is the one that logs most, and exception messages are the likeliest
		// accidental route for content into a log line.
		provider.reset();
		provider.queue(LlmPurpose.EXTRACT, new FakeLlmProvider.Script.InvalidOutput());
		provider.queue(LlmPurpose.EXTRACT, new FakeLlmProvider.Script.InvalidOutput());

		try (LogCapture logs = LogCapture.start()) {
			UUID reportId = postText();
			String responseBody = awaitTerminal(reportId);

			assertNoCanaryIn("the captured log", logs.allText());
			assertNoCanaryInPrompts();
			assertNoCanaryInDatabase();
			assertNoCanaryIn("the response body", responseBody);
		}
	}

	@Test
	@DisplayName("the original filename never reaches a log or the database")
	void filenameLeaksNothing() throws Exception {
		// Lab PDFs are routinely named after the patient, which is why the filename is never
		// stored or logged (LLD 6.3).
		String filename = CANARY_NAME + "-lab-report.pdf";

		try (LogCapture logs = LogCapture.start()) {
			MockMultipartFile file = new MockMultipartFile("file", filename,
					MediaType.APPLICATION_PDF_VALUE, TestPdfs.withText(CANARY_REPORT));

			String body = mockMvc.perform(multipart("/api/reports")
					.file(file)
					.param("consent", "true")
					.header("X-Session-Token", token))
					.andExpect(status().isAccepted())
					.andReturn().getResponse().getContentAsString();

			UUID reportId = idOf(body);
			awaitTerminal(reportId);

			assertNoCanaryIn("the captured log", logs.allText());
			assertNoCanaryInDatabase();
		}
	}

	@Test
	@DisplayName("the masked text that is stored holds placeholders, not canaries")
	void storedTextIsMasked() throws Exception {
		UUID reportId = postText();
		awaitTerminal(reportId);

		String stored = jdbc.sql("select masked_text from report_text where report_id = :id")
				.param("id", reportId)
				.query(String.class)
				.optional()
				.orElseThrow();

		// The only place a placeholder is allowed to be, and the values had to survive next
		// to it or the support check would have dropped every row.
		assertThat(stored).contains("[NAME]").contains("[ID]").contains("[PHONE]").contains("[DOB]");
		for (String canary : CANARIES) {
			assertThat(stored).doesNotContain(canary);
		}
		assertThat(stored).contains("238").contains("Collected: 09/28/2026");
	}

	// --- assertions ----------------------------------------------------------

	private void assertNoCanaryIn(String where, String haystack) {
		for (String canary : CANARIES) {
			assertThat(haystack)
					.as("%s must not contain the canary %s", where, canary)
					.doesNotContain(canary);
		}
	}

	private void assertNoCanaryInPrompts() {
		assertThat(provider.received())
				.as("the model must have been called, or this proves nothing")
				.isNotEmpty();

		for (LlmRequest request : provider.received()) {
			assertNoCanaryIn("an LLM prompt (" + request.purpose() + ")", request.userContent());
			assertNoCanaryIn("an LLM system prompt", request.systemPrompt());
		}
	}

	/**
	 * Dumps every text column of every table and searches the lot.
	 *
	 * <p>Driven off {@code information_schema} rather than a hand-written list of tables, so a
	 * table added in a later phase is covered by this test without anyone remembering to add
	 * it.
	 */
	private void assertNoCanaryInDatabase() {
		List<TextColumn> columns = jdbc.sql("""
				select table_name, column_name
				from information_schema.columns
				where table_schema = 'public'
				  and data_type in ('text', 'character varying', 'varchar', 'jsonb', 'json')
				""")
				.query(TextColumn.class)
				.list();

		assertThat(columns).as("there should be text columns to search").isNotEmpty();

		for (TextColumn column : columns) {
			List<String> values = jdbc.sql("select cast(" + column.columnName() + " as text) from "
					+ column.tableName() + " where " + column.columnName() + " is not null")
					.query(String.class)
					.list();

			for (String value : values) {
				assertNoCanaryIn(column.tableName() + "." + column.columnName(), value);
			}
		}
	}

	// --- helpers -------------------------------------------------------------

	private void scriptModel() {
		// Values that genuinely appear in the report, so validation keeps them and the
		// pipeline reaches DONE - a report that failed validation would exercise less.
		provider.alwaysRespondWith(LlmPurpose.EXTRACT, new ModelExtraction(List.of(
				new ModelExtraction.ModelRow("Total Cholesterol", "238", "mg/dL", "<200"),
				new ModelExtraction.ModelRow("HDL Cholesterol", "38", "mg/dL", ">40"),
				new ModelExtraction.ModelRow("Triglycerides", "140", "mg/dL", "<150")),
				"09/28/2026"));

		provider.alwaysRespondWith(LlmPurpose.SUMMARY, new ModelSummary(
				"Your total cholesterol is above the printed range of <200.",
				List.of("Total Cholesterol is above its range.",
						"HDL Cholesterol is below its range.")));
	}

	private UUID postText() throws Exception {
		String text = String.join("\n", CANARY_REPORT);

		String body = mockMvc.perform(post("/api/reports")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(jdbcJson(text)))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		return idOf(body);
	}

	private UUID postPdf() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "report.pdf",
				MediaType.APPLICATION_PDF_VALUE, TestPdfs.withText(CANARY_REPORT));

		String body = mockMvc.perform(multipart("/api/reports")
				.file(file)
				.param("consent", "true")
				.header("X-Session-Token", token))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		return idOf(body);
	}

	/** Polls until the report is terminal, and returns the final response body. */
	private String awaitTerminal(UUID reportId) {
		Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
			String body = fetch(reportId);
			return body.contains("\"DONE\"") || body.contains("\"FAILED\"");
		});
		return fetch(reportId);
	}

	private String fetch(UUID reportId) {
		try {
			return mockMvc.perform(get("/api/reports/" + reportId)
					.header("X-Session-Token", token))
					.andReturn().getResponse().getContentAsString();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static UUID idOf(String body) {
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	/** JSON-escapes the report into a request body. */
	private static String jdbcJson(String text) {
		String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
		return "{\"text\": \"" + escaped + "\", \"consent\": true}";
	}

	/** One searchable column. */
	public record TextColumn(String tableName, String columnName) {
	}
}
