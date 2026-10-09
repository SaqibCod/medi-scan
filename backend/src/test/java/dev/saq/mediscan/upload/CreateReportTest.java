package dev.saq.mediscan.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestPdfs;

/**
 * {@code POST /api/reports}: every rejection in {@code LLD} 15, and the {@code 202} shape.
 *
 * <p>Status and {@code code} are both asserted on every failure, per
 * {@code backend/CLAUDE.md}: the client switches on {@code code}, so a correct status with the
 * wrong code is still a broken contract.
 */
class CreateReportTest extends PostgresTestBase {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ReportRepository reports;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	TempFileStore tempFiles;

	private String sessionToken;
	private UUID sessionId;

	@BeforeEach
	void freshSession() {
		fixtures.clear();
		sessionToken = fixtures.sessionToken();
		sessionId = fixtures.sessionIdFor(sessionToken);
	}

	// --- the accepted paths -------------------------------------------------

	@Test
	@DisplayName("a sample returns 202 with the contract's body and a Location header")
	void acceptsSample() throws Exception {
		mockMvc.perform(json("""
				{"sampleId": "lipid-panel", "consent": true}"""))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location",
						org.hamcrest.Matchers.matchesPattern("/api/reports/[0-9a-f-]{36}")))
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.sourceType").value("SAMPLE"))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				// The 202 body carries no result or error yet.
				.andExpect(jsonPath("$.result").doesNotExist())
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	@DisplayName("pasted text returns 202 with sourceType TEXT")
	void acceptsText() throws Exception {
		mockMvc.perform(json("""
				{"text": "Total Cholesterol 238 mg/dL <200 H\\nHDL Cholesterol 38 mg/dL >40 L",
				 "consent": true}"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.sourceType").value("TEXT"));
	}

	@Test
	@DisplayName("a text PDF returns 202 with sourceType PDF")
	void acceptsPdf() throws Exception {
		mockMvc.perform(pdfUpload(TestPdfs.withText(List.of(
				"Total Cholesterol 238 mg/dL <200 H",
				"HDL Cholesterol 38 mg/dL >40 L",
				"LDL Cholesterol 172 mg/dL <100 H"))))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.sourceType").value("PDF"));
	}

	@Test
	@DisplayName("the report is persisted, owned by the caller's session")
	void persistsReport() throws Exception {
		String body = mockMvc.perform(json("""
				{"sampleId": "cbc", "consent": true}"""))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		UUID reportId = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

		// Deliberately not asserting PENDING. A worker picks the job up the moment the row
		// exists, so by the time this line runs the status is a race - PENDING, PROCESSING,
		// or already terminal. The endpoint's guarantee is that the row exists and belongs to
		// the caller; what the pipeline then does with it is ReportPipelineTest's subject.
		assertThat(reports.findOwnedBySession(reportId, sessionId, Instant.now()))
				.isPresent()
				.get()
				.satisfies(report -> {
					assertThat(report.getSourceType()).isEqualTo(SourceType.SAMPLE);
					assertThat(report.getCreatedAt()).isNotNull();
					assertThat(report.getExpiresAt()).isAfter(report.getCreatedAt());
				});
	}

	@Test
	@DisplayName("a report belongs only to the session that created it")
	void reportIsOwnerScoped() throws Exception {
		String body = mockMvc.perform(json("""
				{"sampleId": "cbc", "consent": true}"""))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		UUID reportId = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
		UUID otherSession = fixtures.session();

		// CLAUDE.md rule 7, at the repository level. The endpoint test for this is in
		// ReportResultsTest; this is the layer underneath it.
		assertThat(reports.findOwnedBySession(reportId, otherSession, Instant.now())).isEmpty();
	}

	// --- consent ------------------------------------------------------------

	@Test
	@DisplayName("missing consent is 400 VALIDATION_ERROR naming the field")
	void requiresConsent() throws Exception {
		mockMvc.perform(json("""
				{"sampleId": "lipid-panel"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("consent"));
	}

	@Test
	@DisplayName("consent false is refused too")
	void refusesConsentFalse() throws Exception {
		mockMvc.perform(json("""
				{"sampleId": "lipid-panel", "consent": false}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("consent"));
	}

	@Test
	@DisplayName("a file upload without the consent part is refused")
	void requiresConsentOnUpload() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "report.pdf",
				MediaType.APPLICATION_PDF_VALUE, TestPdfs.withText(List.of("Glucose 99 mg/dL")));

		mockMvc.perform(multipart("/api/reports").file(file)
				.header("X-Session-Token", sessionToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("consent"));
	}

	// --- exactly one source -------------------------------------------------

	@Test
	@DisplayName("both text and sampleId is 400 VALIDATION_ERROR")
	void refusesBothSources() throws Exception {
		mockMvc.perform(json("""
				{"text": "Total Cholesterol 238 mg/dL <200 and more text here",
				 "sampleId": "cbc", "consent": true}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	@DisplayName("neither text nor sampleId is 400 VALIDATION_ERROR")
	void refusesNeitherSource() throws Exception {
		mockMvc.perform(json("""
				{"consent": true}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	// --- text length --------------------------------------------------------

	@Test
	@DisplayName("text under 20 characters is refused")
	void refusesShortText() throws Exception {
		mockMvc.perform(json("""
				{"text": "Glucose 99", "consent": true}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("text"));
	}

	@Test
	@DisplayName("text over 50,000 characters is refused")
	void refusesLongText() throws Exception {
		String tooLong = "Total Cholesterol 238 mg/dL <200 H\\n".repeat(2000);

		mockMvc.perform(json("{\"text\": \"" + tooLong + "\", \"consent\": true}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("text"));
	}

	// --- samples ------------------------------------------------------------

	@Test
	@DisplayName("an unknown sampleId is 404 SAMPLE_NOT_FOUND")
	void refusesUnknownSample() throws Exception {
		mockMvc.perform(json("""
				{"sampleId": "no-such-sample", "consent": true}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SAMPLE_NOT_FOUND"));
	}

	// --- file type and signature -------------------------------------------

	@Test
	@DisplayName("a PNG is 415 UNSUPPORTED_FILE_TYPE until phase 4")
	void refusesPng() throws Exception {
		byte[] png = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x11, 0x22 };
		MockMultipartFile file = new MockMultipartFile("file", "scan.png",
				MediaType.IMAGE_PNG_VALUE, png);

		// Declared consistently and a genuine PNG. The only thing wrong with it is that no
		// extractor can read an image yet (contract section 4.1).
		mockMvc.perform(upload(file))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
	}

	@Test
	@DisplayName("a type the API does not accept at all is 415")
	void refusesUnsupportedType() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "report.zip",
				"application/zip", "PK zip".getBytes());

		mockMvc.perform(upload(file))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
	}

	@Test
	@DisplayName("bytes that contradict the declared type is 400 FILE_SIGNATURE_MISMATCH")
	void refusesSignatureMismatch() throws Exception {
		byte[] actuallyPng = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2 };
		MockMultipartFile file = new MockMultipartFile("file", "report.pdf",
				MediaType.APPLICATION_PDF_VALUE, actuallyPng);

		mockMvc.perform(upload(file))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FILE_SIGNATURE_MISMATCH"));
	}

	@Test
	@DisplayName("a file whose bytes match nothing is 400 FILE_SIGNATURE_MISMATCH")
	void refusesUnknownSignature() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "report.pdf",
				MediaType.APPLICATION_PDF_VALUE, "this is just text".getBytes());

		mockMvc.perform(upload(file))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FILE_SIGNATURE_MISMATCH"));
	}

	@Test
	@DisplayName("an empty file is refused")
	void refusesEmptyFile() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "report.pdf",
				MediaType.APPLICATION_PDF_VALUE, new byte[0]);

		mockMvc.perform(upload(file))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	// --- authentication -----------------------------------------------------

	@Test
	@DisplayName("no session token is 401 SESSION_INVALID")
	void requiresSession() throws Exception {
		mockMvc.perform(post("/api/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sampleId": "lipid-panel", "consent": true}"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("SESSION_INVALID"));
	}

	@Test
	@DisplayName("an unknown session token is 401 SESSION_INVALID")
	void refusesUnknownSession() throws Exception {
		mockMvc.perform(post("/api/reports")
				.header("X-Session-Token", "a".repeat(43))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sampleId": "lipid-panel", "consent": true}"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("SESSION_INVALID"));
	}

	// --- cleanup ------------------------------------------------------------

	@Test
	@DisplayName("a rejected upload leaves no temp file behind")
	void rejectedUploadLeavesNoTempFile() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "report.pdf",
				MediaType.APPLICATION_PDF_VALUE, "this is just text".getBytes());

		mockMvc.perform(upload(file)).andExpect(status().isBadRequest());

		assertThat(tempFileCount()).isZero();
	}

	@Test
	@DisplayName("a rejected PNG leaves no temp file behind")
	void rejectedPngLeavesNoTempFile() throws Exception {
		byte[] png = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2 };
		MockMultipartFile file = new MockMultipartFile("file", "scan.png",
				MediaType.IMAGE_PNG_VALUE, png);

		mockMvc.perform(upload(file)).andExpect(status().isUnsupportedMediaType());

		// The signature is checked before the file is written, so there was never a file.
		assertThat(tempFileCount()).isZero();
	}

	// --- helpers ------------------------------------------------------------

	private RequestBuilder json(String body) {
		return post("/api/reports")
				.header("X-Session-Token", sessionToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private RequestBuilder pdfUpload(byte[] bytes) {
		return upload(new MockMultipartFile("file", "report.pdf",
				MediaType.APPLICATION_PDF_VALUE, bytes));
	}

	private RequestBuilder upload(MockMultipartFile file) {
		return multipart("/api/reports")
				.file(file)
				.param("consent", "true")
				.header("X-Session-Token", sessionToken);
	}

	private long tempFileCount() throws Exception {
		Path directory = tempFiles.directory();
		if (!Files.exists(directory)) {
			return 0;
		}
		try (var files = Files.list(directory)) {
			return files.count();
		}
	}
}
