package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.report.TextSource;
import dev.saq.mediscan.support.TestImages;
import dev.saq.mediscan.support.TestProperties;

/**
 * {@link ImageOcrExtractor}: a photo in, text and confidences out.
 *
 * <p>Uses {@link FakeOcrEngine}, so these assert the extractor's own decisions - what counts as
 * unreadable, which failure code a given OCR failure becomes, whether the prepared file is
 * cleaned up - rather than Tesseract's accuracy. The real thing is exercised by the
 * {@code ocr}-tagged integration tests.
 */
class ImageOcrExtractorTest {

	@TempDir
	Path directory;

	private static final String LIPID_PANEL = """
			RIVERBEND CLINICAL LABORATORY
			Total Cholesterol   238   mg/dL   <200   H
			LDL Cholesterol   162   mg/dL   <100   H
			HDL Cholesterol   52   mg/dL   >40""";

	private ImageOcrExtractor extractor(OcrEngine engine) {
		return extractor(engine, TestProperties.defaults(), () -> true);
	}

	private ImageOcrExtractor extractor(OcrEngine engine, MediScanProperties properties,
			OcrAvailability availability) {

		return new ImageOcrExtractor(new ImagePreparer(properties), engine,
				new OcrGate(properties, Clock.systemUTC()), availability, properties);
	}

	private JobSource.ImageFile photo() throws IOException {
		Path image = TestImages.writePng(directory, 1600, 900);
		return new JobSource.ImageFile(image, JobSource.ImageKind.PNG);
	}

	// --- the happy path -------------------------------------------------------

	@Test
	@DisplayName("a readable photo gives OCR text, textSource OCR, and a confidence index")
	void readsAPhoto() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine().returning(LIPID_PANEL, 94);

		ExtractedText extracted = extractor(engine).extract(photo());

		assertThat(extracted.text()).contains("Total Cholesterol").contains("238");
		assertThat(extracted.textSource()).isEqualTo(TextSource.OCR);
		assertThat(extracted.pageCount()).isEqualTo(1);
		assertThat(extracted.confidence().isEmpty()).isFalse();
		assertThat(extracted.confidence()
				.lowestConfidenceFor("238", line -> true)).hasValue(94.0);
	}

	@Test
	@DisplayName("the engine is given a prepared file, not the upload")
	void ocrsThePreparedFile() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine().returning(LIPID_PANEL, 94);
		JobSource.ImageFile source = photo();

		extractor(engine).extract(source);

		assertThat(engine.callCount()).isEqualTo(1);
		Path given = engine.calls().getFirst().image();
		assertThat(given).isNotEqualTo(source.path());
		assertThat(given.getFileName().toString()).startsWith("ocr-").endsWith(".png");
	}

	@Test
	@DisplayName("the extractor claims IMAGE, which is what opens the upload gate")
	void claimsImageSourceType() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine().returning(LIPID_PANEL, 94);

		assertThat(extractor(engine).supports(SourceType.IMAGE)).isTrue();
		assertThat(extractor(engine).supports(SourceType.PDF)).isFalse();
		assertThat(extractor(engine).supports(photo())).isTrue();
	}

	@Test
	@DisplayName("with OCR unavailable it claims nothing, so images are refused at upload")
	void claimsNothingWhenUnavailable() throws IOException {
		// This is how OCR_ENABLED=false restores the phase-2 behaviour exactly: the registry
		// finds no extractor for IMAGE, and UploadValidator answers 415.
		ImageOcrExtractor off = extractor(new FakeOcrEngine(), TestProperties.defaults(),
				() -> false);

		assertThat(off.supports(SourceType.IMAGE)).isFalse();
		assertThat(off.supports(photo())).isFalse();
	}

	// --- what counts as unreadable --------------------------------------------

	@Test
	@DisplayName("a photo OCR found no words in is UNREADABLE")
	void emptyPageIsUnreadable() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine().returning(OcrPage.EMPTY);

		assertThatThrownBy(() -> extractor(engine).extract(photo()))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("a page read with very low confidence is UNREADABLE, not fed to the model")
	void garbagePageIsUnreadable() throws IOException {
		// Below the page confidence floor the text is noise. Passing it on would turn an
		// unreadable photo into invented-looking results, which is worse than failing.
		FakeOcrEngine engine = new FakeOcrEngine().returning(LIPID_PANEL, 12);

		assertThatThrownBy(() -> extractor(engine).extract(photo()))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("a page just above the confidence floor is accepted")
	void justAboveTheFloorIsAccepted() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine().returning(LIPID_PANEL, 31);

		assertThat(extractor(engine).extract(photo()).textSource()).isEqualTo(TextSource.OCR);
	}

	@Test
	@DisplayName("too little text overall is UNREADABLE")
	void tooLittleTextIsUnreadable() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine().returning("Lab 1", 95);

		assertThatThrownBy(() -> extractor(engine).extract(photo()))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("more text than the limit is DOCUMENT_TOO_LONG")
	void tooMuchTextIsDocumentTooLong() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine()
				.returning("Potassium 5.4 mmol/L 3.5-5.0\n".repeat(3000), 95);

		assertThatThrownBy(() -> extractor(engine).extract(photo()))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.DOCUMENT_TOO_LONG);
	}

	// --- OCR failures become report failures, with no new codes ----------------

	@Test
	@DisplayName("a timeout becomes EXTRACTION_FAILED")
	void timeoutBecomesExtractionFailed() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine()
				.failing(OcrFailedException.Reason.TIMEOUT);

		assertThatThrownBy(() -> extractor(engine).extract(photo()))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.EXTRACTION_FAILED);
	}

	@Test
	@DisplayName("a non-zero exit becomes UNREADABLE")
	void exitCodeBecomesUnreadable() throws IOException {
		// Tesseract refusing the file is a statement about the file, not about us.
		FakeOcrEngine engine = new FakeOcrEngine()
				.failing(OcrFailedException.Reason.EXIT_CODE);

		assertThatThrownBy(() -> extractor(engine).extract(photo()))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("an output flood becomes EXTRACTION_FAILED")
	void outputCapBecomesExtractionFailed() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine()
				.failing(OcrFailedException.Reason.OUTPUT_CAP);

		assertThatThrownBy(() -> extractor(engine).extract(photo()))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.EXTRACTION_FAILED);
	}

	// --- temp files -----------------------------------------------------------

	@Test
	@DisplayName("the prepared file is deleted on success")
	void deletesPreparedFileOnSuccess() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine().returning(LIPID_PANEL, 94);

		extractor(engine).extract(photo());

		assertThat(preparedFiles()).isEmpty();
	}

	@Test
	@DisplayName("the prepared file is deleted when OCR fails")
	void deletesPreparedFileOnFailure() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine()
				.failing(OcrFailedException.Reason.TIMEOUT);

		assertThatThrownBy(() -> extractor(engine).extract(photo()))
				.isInstanceOf(ReportFailure.class);

		assertThat(preparedFiles()).isEmpty();
	}

	@Test
	@DisplayName("the upload itself is left for the job to delete")
	void leavesTheUploadAlone() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine().returning(LIPID_PANEL, 94);
		JobSource.ImageFile source = photo();

		extractor(engine).extract(source);

		// The job's finally block owns the upload, as it did before this phase.
		assertThat(source.path()).exists();
	}

	@Test
	@DisplayName("a rejected image never reaches the engine")
	void rejectedImageIsNotOcrd() throws IOException {
		FakeOcrEngine engine = new FakeOcrEngine().returning(LIPID_PANEL, 94);
		Path bomb = TestImages.write(directory, "bomb.png",
				TestImages.pngHeaderClaiming(50_000, 50_000));

		assertThatThrownBy(() -> extractor(engine)
				.extract(new JobSource.ImageFile(bomb, JobSource.ImageKind.PNG)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);

		assertThat(engine.callCount()).isZero();
	}

	@Test
	@DisplayName("the job source hides nothing it should not, and carries no filename")
	void jobSourceCarriesNoFilename() throws IOException {
		JobSource.ImageFile source = photo();

		assertThat(source.sourceType()).isEqualTo(SourceType.IMAGE);
		assertThat(source.kind()).isEqualTo(JobSource.ImageKind.PNG);
	}

	private List<Path> preparedFiles() throws IOException {
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(file -> file.getFileName().toString().startsWith("ocr-")).toList();
		}
	}
}
