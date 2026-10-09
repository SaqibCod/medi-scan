package dev.saq.mediscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
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
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestPdfs;
import dev.saq.mediscan.upload.TempFileStore;

/**
 * The whole phase 2 deliverable, over HTTP: upload, poll, read results.
 *
 * <p>This is the test that matches what the task's manual verification does with curl, so a
 * green run here means the curl flow will work. Every other test checks a stage; this one
 * checks the thing a user actually does.
 */
class ReportEndToEndTest extends PostgresTestBase {

	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	FakeLlmProvider provider;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	TempFileStore tempFiles;

	@Autowired
	JdbcClient jdbc;

	private String token;

	@BeforeEach
	void reset() {
		fixtures.clear();
		provider.reset();
		jdbc.sql("delete from llm_usage").update();
		token = fixtures.sessionToken();
	}

	@AfterEach
	void tempDirectoryIsEmpty() throws Exception {
		// LLD 17.2 item 14, asserted after every case in this class rather than once: an
		// uploaded file is the only unmasked copy of a report, so "no file left behind" has to
		// hold on the failure paths too.
		try (var files = Files.list(tempFiles.directory())) {
			assertThat(files.toList())
					.as("the upload temp directory must be empty after a run")
					.isEmpty();
		}
	}

	@Test
	@DisplayName("a sample report reaches DONE with results")
	void sampleReachesDone() throws Exception {
		scriptLipidPanel();

		UUID reportId = create("""
				{"sampleId": "lipid-panel", "consent": true}""");

		awaitStatus(reportId, "DONE");

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(jsonPath("$.status").value("DONE"))
				.andExpect(jsonPath("$.sourceType").value("SAMPLE"))
				.andExpect(jsonPath("$.result.collectedOn").value("2026-09-28"))
				.andExpect(jsonPath("$.result.biomarkers.length()").value(3))
				.andExpect(jsonPath("$.result.counts.high").value(1))
				.andExpect(jsonPath("$.result.counts.low").value(1))
				.andExpect(jsonPath("$.result.counts.normal").value(1))
				.andExpect(jsonPath("$.result.summary").isNotEmpty())
				.andExpect(jsonPath("$.result.biomarkers[0].biomarkerSlug")
						.value("total-cholesterol"));
	}

	@Test
	@DisplayName("pasted text reaches DONE")
	void pastedTextReachesDone() throws Exception {
		scriptLipidPanel();

		UUID reportId = create("""
				{"text": "Patient Name: Jane Q. Roe\\nCollected: 09/28/2026\\n\
				Total Cholesterol 238 mg/dL <200 H\\nHDL Cholesterol 38 mg/dL >40 L\\n\
				Triglycerides 140 mg/dL <150", "consent": true}""");

		awaitStatus(reportId, "DONE");

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(jsonPath("$.sourceType").value("TEXT"))
				.andExpect(jsonPath("$.result.biomarkers.length()").value(3));
	}

	@Test
	@DisplayName("a text PDF reaches DONE")
	void textPdfReachesDone() throws Exception {
		scriptLipidPanel();

		UUID reportId = createPdf(TestPdfs.withText(List.of(
				"NORTHSIDE COMMUNITY LABORATORY",
				"Patient Name: Jane Q. Roe",
				"Collected: 09/28/2026",
				"Total Cholesterol 238 mg/dL <200 H",
				"HDL Cholesterol 38 mg/dL >40 L",
				"Triglycerides 140 mg/dL <150")));

		awaitStatus(reportId, "DONE");

		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(jsonPath("$.sourceType").value("PDF"))
				.andExpect(jsonPath("$.result.biomarkers.length()").value(3));
	}

	@Test
	@DisplayName("a scanned-style PDF ends FAILED with UNREADABLE")
	void scannedPdfFailsUnreadable() throws Exception {
		UUID reportId = createPdf(TestPdfs.imageOnly());

		awaitStatus(reportId, "FAILED");

		// Until phase 4 adds OCR there is nothing to fall back to, so this is the honest
		// outcome rather than an empty result (contract 4.1, dataflow 4.2).
		mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.error.code").value("UNREADABLE"))
				.andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.nullValue()));

		// No model call was made, so a scanned PDF costs nothing from the daily budget.
		assertThat(provider.callCount()).isZero();
	}

	@Test
	@DisplayName("a PNG is refused at upload, before any job exists")
	void pngIsRefused() throws Exception {
		byte[] png = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2 };
		MockMultipartFile file = new MockMultipartFile("file", "scan.png",
				MediaType.IMAGE_PNG_VALUE, png);

		mockMvc.perform(multipart("/api/reports")
				.file(file)
				.param("consent", "true")
				.header("X-Session-Token", token))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
	}

	@Test
	@DisplayName("all three bundled samples reach DONE")
	void allSamplesReachDone() throws Exception {
		for (String sampleId : List.of("lipid-panel", "cbc", "thyroid-panel")) {
			provider.reset();
			scriptFromSample(sampleId);

			UUID reportId = create("{\"sampleId\": \"" + sampleId + "\", \"consent\": true}");
			awaitStatus(reportId, "DONE");

			mockMvc.perform(get("/api/reports/" + reportId).header("X-Session-Token", token))
					.andExpect(jsonPath("$.status").value("DONE"))
					.andExpect(jsonPath("$.result.biomarkers.length()")
							.value(org.hamcrest.Matchers.greaterThan(0)));
		}
	}

	@Test
	@DisplayName("the polling sequence a client follows works")
	void pollingSequenceWorks() throws Exception {
		scriptLipidPanel();

		UUID reportId = create("""
				{"sampleId": "lipid-panel", "consent": true}""");

		// What the client does: poll the Location URL until the status is terminal. The first
		// read may legitimately be any of the four statuses.
		String body = awaitStatus(reportId, "DONE");

		assertThat(body).contains("\"status\":\"DONE\"");
		assertThat(body).contains("\"result\"");
	}

	// --- helpers -------------------------------------------------------------

	private void scriptLipidPanel() {
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

	/** Rows that genuinely appear in each bundled sample, so validation keeps them. */
	private void scriptFromSample(String sampleId) {
		List<ModelExtraction.ModelRow> rows = switch (sampleId) {
			case "lipid-panel" -> List.of(
					new ModelExtraction.ModelRow("Total Cholesterol", "238", "mg/dL", "<200"),
					new ModelExtraction.ModelRow("HDL Cholesterol", "38", "mg/dL", ">40"));
			case "cbc" -> List.of(
					new ModelExtraction.ModelRow("Hemoglobin", "14.6", "g/dL", "13.5-17.5"),
					new ModelExtraction.ModelRow("Platelet Count", "245", "x10^3/uL", "150-400"));
			case "thyroid-panel" -> List.of(
					new ModelExtraction.ModelRow("TSH", "0.21", "uIU/mL", "0.45-4.50"),
					new ModelExtraction.ModelRow("Thyroid Peroxidase Antibodies", "Negative",
							null, "Negative"));
			default -> throw new IllegalArgumentException(sampleId);
		};

		provider.alwaysRespondWith(LlmPurpose.EXTRACT, new ModelExtraction(rows, null));
		provider.alwaysRespondWith(LlmPurpose.SUMMARY,
				new ModelSummary("Here is what your results show.", List.of()));
	}

	private UUID create(String json) throws Exception {
		String body = mockMvc.perform(post("/api/reports")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private UUID createPdf(byte[] pdf) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "report.pdf",
				MediaType.APPLICATION_PDF_VALUE, pdf);

		String body = mockMvc.perform(multipart("/api/reports")
				.file(file)
				.param("consent", "true")
				.header("X-Session-Token", token))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	/** Polls like the client does, and returns the final body. */
	private String awaitStatus(UUID reportId, String expected) {
		Awaitility.await().atMost(TIMEOUT)
				.until(() -> fetch(reportId).contains("\"status\":\"" + expected + "\""));
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
}
