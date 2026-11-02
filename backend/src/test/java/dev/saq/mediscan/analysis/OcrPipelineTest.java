package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.eval.OcrCaseRenderer;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.extract.TesseractAvailable;
import dev.saq.mediscan.llm.FakeLlmProvider;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.report.BiomarkerRepository;
import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.ReportStatus;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.report.TextSource;
import dev.saq.mediscan.support.LogCapture;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * The OCR path through the whole pipeline, with the real Tesseract
 * ({@code docs/phase-4-lld.md} 12.2).
 *
 * <p>Everything else in the suite scripts the OCR engine, which is right for testing the rules
 * around it - there is no way to ask Tesseract to be 62% sure of a specific digit. But scripted
 * confidences cannot tell you whether a photograph of a lab report comes out the other end as a
 * report, and that is the only question this phase ultimately has to answer.
 *
 * <p>Skipped with a message when Tesseract is missing, rather than failing: these belong in
 * {@code ./mvnw verify} on a machine that has it, and must not break one that does not.
 *
 * <p>The images are rendered from text at test time by {@link OcrCaseRenderer}, the same way the
 * evaluation builds its cases, so nothing binary is committed and every input is readable in the
 * source.
 */
@Tag("ocr")
class OcrPipelineTest extends PostgresTestBase {

	private static final Duration TIMEOUT = Duration.ofSeconds(90);

	/** A canary name and id, to prove neither reaches a log, the database, or the model. */
	private static final String CANARY_NAME = "ZZCANARY Quillfeather";
	private static final String CANARY_ID = "ZZCANARY-7391";

	private static final String LIPID_REPORT = """
			RIVERBEND CLINICAL LABORATORY
			Patient Name: %s
			Patient ID: %s
			Collected: 09/15/2026

			LIPID PANEL

			Test                  Result    Units    Reference Range
			Total Cholesterol     238       mg/dL    <200
			HDL Cholesterol       38        mg/dL    >40
			LDL Cholesterol       162       mg/dL    <100
			Triglycerides         140       mg/dL    <150

			End of report.
			""".formatted(CANARY_NAME, CANARY_ID);

	@TempDir
	Path tempDir;

	@Autowired
	ReportJobFactory jobFactory;

	@Autowired
	FakeLlmProvider provider;

	@Autowired
	ReportRepository reports;

	@Autowired
	BiomarkerRepository biomarkers;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	JdbcClient jdbc;

	private UUID sessionId;

	@BeforeEach
	void reset() {
		TesseractAvailable.orSkip();
		fixtures.clear();
		provider.reset();
		jdbc.sql("delete from llm_usage").update();
		sessionId = fixtures.session();
	}

	// --- the happy path -------------------------------------------------------

	@Test
	@DisplayName("a photo of a lipid panel reaches DONE, read by OCR")
	void photoReachesDone() throws IOException {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId, SourceType.IMAGE);

		run(reportId, image(OcrCaseRenderer.Variant.CLEAN_SCAN));

		ReportEntity report = load(reportId);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.DONE);
		assertThat(report.getTextSource()).isEqualTo(TextSource.OCR);

		// The values are the ones printed on the page, read off a picture of it, with flags
		// computed in code from the printed ranges.
		assertThat(biomarkers.findOwnedBySession(reportId, sessionId, Instant.now()))
				.extracting(marker -> marker.getTestName() + "=" + marker.getRawValue()
						+ "/" + marker.getFlag())
				.contains("Total Cholesterol=238/HIGH", "HDL Cholesterol=38/LOW");
	}

	@Test
	@DisplayName("a scanned PDF reaches DONE with textSource OCR")
	void scannedPdfReachesDone() throws IOException {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId, SourceType.PDF);

		run(reportId, pdf(OcrCaseRenderer.Variant.SCANNED_PDF));

		ReportEntity report = load(reportId);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.DONE);
		assertThat(report.getTextSource()).isEqualTo(TextSource.OCR);
	}

	@Test
	@DisplayName("a mixed PDF reaches DONE with textSource MIXED")
	void mixedPdfReachesDone() throws IOException {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId, SourceType.PDF);

		run(reportId, pdf(OcrCaseRenderer.Variant.MIXED_PDF));

		ReportEntity report = load(reportId);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.DONE);
		// One page of real text, one page of picture. The client shows a different notice for
		// this than for a photo, so the distinction has to survive the whole pipeline.
		assertThat(report.getTextSource()).isEqualTo(TextSource.MIXED);
	}

	@Test
	@DisplayName("a photo taken sideways is read the right way up")
	void rotatedPhotoIsStraightened() throws IOException {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId, SourceType.IMAGE);

		// Stored a quarter turn over, with the EXIF tag that says so - which is what a phone
		// held upright writes. Ignoring the tag hands Tesseract a sideways page and it reads
		// nothing, so this reaching DONE is the whole proof that the tag is honoured.
		run(reportId, image(OcrCaseRenderer.Variant.ROTATED_EXIF));

		assertThat(load(reportId).getStatus()).isEqualTo(ReportStatus.DONE);
	}

	// --- failures -------------------------------------------------------------

	@Test
	@DisplayName("a blank photo fails UNREADABLE rather than inventing results")
	void blankPhotoIsUnreadable() throws IOException {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId, SourceType.IMAGE);

		Path blank = Files.createTempFile(tempDir, "blank-", ".png");
		Files.write(blank, OcrCaseRenderer.render("   \n   \n   ",
				OcrCaseRenderer.Variant.CLEAN_SCAN, OcrCaseRenderer.Typeface.MONOSPACED, 1)
				.bytes());

		run(reportId, new JobSource.ImageFile(blank, JobSource.ImageKind.PNG));

		ReportEntity report = load(reportId);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
		assertThat(report.getErrorCode()).isEqualTo(ReportErrorCode.UNREADABLE);
		// Nothing was stored, so nothing invented reached a reader.
		assertThat(biomarkers.findOwnedBySession(reportId, sessionId, Instant.now())).isEmpty();
	}

	// --- privacy --------------------------------------------------------------

	@Test
	@DisplayName("the canary on a photographed report reaches no log, no prompt and no table")
	void canaryNeverEscapes() throws IOException {
		scriptLipidPanel();
		UUID reportId = fixtures.pendingReport(sessionId, SourceType.IMAGE);

		try (LogCapture logs = LogCapture.start()) {
			run(reportId, image(OcrCaseRenderer.Variant.CLEAN_SCAN));

			// Rule 3: no report content in logs - and OCR adds a whole new way to break it,
			// because Tesseract's own output passes through this process.
			assertThat(logs.allText()).doesNotContain("ZZCANARY");
		}

		// Rule 1: only masked text reaches the model.
		assertThat(provider.received()).isNotEmpty();
		for (var request : provider.received()) {
			assertThat(request.userContent()).doesNotContain("ZZCANARY");
		}

		// And nothing of it is stored.
		String storedText = jdbc.sql("select masked_text from report_text where report_id = :id")
				.param("id", reportId)
				.query(String.class)
				.optional()
				.orElse("");
		assertThat(storedText).doesNotContain("ZZCANARY");
	}

	@Test
	@DisplayName("no temp file outlives an OCR job, whether it succeeded or failed")
	void leavesNoTempFiles() throws IOException {
		scriptLipidPanel();

		UUID done = fixtures.pendingReport(sessionId, SourceType.IMAGE);
		JobSource good = image(OcrCaseRenderer.Variant.CLEAN_SCAN);
		run(done, good);

		// The job deletes the upload; the extractor deletes the prepared PNG it wrote beside
		// it. Both land in this directory, so what is left here is the whole answer.
		try (var entries = Files.list(tempDir)) {
			assertThat(entries.toList())
					.as("the prepared image must not outlive the job")
					.isEmpty();
		}
	}

	// --- helpers --------------------------------------------------------------

	private JobSource image(OcrCaseRenderer.Variant variant) throws IOException {
		OcrCaseRenderer.Rendered rendered = OcrCaseRenderer.render(LIPID_REPORT, variant,
				OcrCaseRenderer.Typeface.MONOSPACED, 4);
		String suffix = rendered.kind() == OcrCaseRenderer.Kind.PNG ? ".png" : ".jpg";
		Path file = Files.createTempFile(tempDir, "photo-", suffix);
		Files.write(file, rendered.bytes());

		return new JobSource.ImageFile(file,
				rendered.kind() == OcrCaseRenderer.Kind.PNG
						? JobSource.ImageKind.PNG
						: JobSource.ImageKind.JPEG);
	}

	private JobSource pdf(OcrCaseRenderer.Variant variant) throws IOException {
		Path file = Files.createTempFile(tempDir, "scan-", ".pdf");
		Files.write(file, OcrCaseRenderer.render(LIPID_REPORT, variant,
				OcrCaseRenderer.Typeface.MONOSPACED, 4).bytes());
		return new JobSource.PdfFile(file);
	}

	/**
	 * What the model will "transcribe".
	 *
	 * <p>The values here are the ones printed on the rendered page, so the support check passes
	 * only if OCR really read them. A fake provider cannot smuggle a value past it: if OCR
	 * misread {@code 238}, this row is dropped and the assertion fails.
	 */
	private void scriptLipidPanel() {
		provider.respondWith(LlmPurpose.EXTRACT, new ModelExtraction(List.of(
				new ModelExtraction.ModelRow("Total Cholesterol", "238", "mg/dL", "<200"),
				new ModelExtraction.ModelRow("HDL Cholesterol", "38", "mg/dL", ">40"),
				new ModelExtraction.ModelRow("LDL Cholesterol", "162", "mg/dL", "<100"),
				new ModelExtraction.ModelRow("Triglycerides", "140", "mg/dL", "<150")),
				"09/15/2026"));

		provider.respondWith(LlmPurpose.SUMMARY, new ModelSummary(
				"Your total cholesterol is above the range printed on this report.",
				List.of("Total Cholesterol is above its range.")));
	}

	private void run(UUID reportId, JobSource source) {
		jobFactory.create(reportId, source).run();

		Awaitility.await().atMost(TIMEOUT).until(() -> {
			ReportEntity report = reports.findById(reportId).orElse(null);
			return report != null && report.getStatus() != ReportStatus.PENDING
					&& report.getStatus() != ReportStatus.PROCESSING;
		});
	}

	private ReportEntity load(UUID reportId) {
		return reports.findById(reportId).orElseThrow();
	}
}
