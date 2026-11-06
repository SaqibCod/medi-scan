package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.report.TextSource;
import dev.saq.mediscan.support.TestPdfs;
import dev.saq.mediscan.support.TestProperties;

/**
 * PDF text extraction ({@code LLD} 9.1).
 *
 * <p>A plain unit test: {@link PdfTextExtractor} takes properties and a file and returns
 * text, with no Spring context and no database, so these run in milliseconds.
 */
class PdfTextExtractorTest {

	@TempDir
	Path tempDir;

	/**
	 * The phase-2 extractor: OCR unavailable, so the scanned-page fallback is switched off.
	 *
	 * <p>Every text-layer test below runs against this on purpose. The fallback has to be
	 * additive - a PDF with a text layer must be read identically whether or not Tesseract is
	 * installed - and the only way to keep proving that is to keep testing the off state.
	 */
	private final PdfTextExtractor extractor = withoutOcr(TestProperties.defaults());

	private static PdfTextExtractor withoutOcr(MediScanProperties properties) {
		return new PdfTextExtractor(
				new ScannedPageReader(new FakeOcrEngine(), properties),
				new OcrGate(properties, Clock.systemUTC()), () -> false, properties);
	}

	/** An extractor that will OCR low-text pages, with a scripted engine behind it. */
	private static PdfTextExtractor withOcr(OcrEngine engine, MediScanProperties properties) {
		return new PdfTextExtractor(new ScannedPageReader(engine, properties),
				new OcrGate(properties, Clock.systemUTC()), () -> true, properties);
	}

	private static PdfTextExtractor withOcr(OcrEngine engine) {
		return withOcr(engine, TestProperties.defaults());
	}

	@Test
	@DisplayName("extracts the text of a generated PDF")
	void extractsText() {
		Path pdf = pdf(TestPdfs.withText(List.of(
				"NORTHSIDE COMMUNITY LABORATORY",
				"Patient Name: Jane Q. Roe",
				"Total Cholesterol 238 mg/dL <200 H",
				"HDL Cholesterol 38 mg/dL >40 L")));

		ExtractedText extracted = extractor.extract(new JobSource.PdfFile(pdf));

		assertThat(extracted.pageCount()).isEqualTo(1);
		assertThat(extracted.lowTextPages()).isZero();
		assertThat(extracted.text()).contains("Total Cholesterol");
		// The values and the printed range have to survive extraction byte for byte, because
		// everything downstream re-parses them from this string.
		assertThat(extracted.text()).contains("238").contains("<200");
		assertThat(extracted.text()).contains("38").contains(">40");
	}

	@Test
	@DisplayName("keeps each result row on one line")
	void keepsRowsOnOneLine() {
		Path pdf = pdf(TestPdfs.withText(List.of(
				"TEST                   RESULT   UNIT    REFERENCE   FLAG",
				"Total Cholesterol 238 mg/dL <200 H",
				"HDL Cholesterol 38 mg/dL >40 L",
				"Triglycerides 140 mg/dL <150")));

		String text = extractor.extract(new JobSource.PdfFile(pdf)).text();

		// setSortByPosition(true) earns its place here: without reading order, a table row can
		// come back with its columns interleaved across lines, and then no line looks like a
		// result row to the masker or the model.
		assertThat(text.lines().filter(line -> line.contains("238")).findFirst().orElseThrow())
				.contains("Total Cholesterol")
				.contains("mg/dL")
				.contains("<200");
	}

	@Test
	@DisplayName("counts pages and joins them with a blank line")
	void handlesMultiplePages() {
		Path pdf = pdf(TestPdfs.withPages(List.of(
				List.of("Page one: Total Cholesterol 238 mg/dL <200 H",
						"Page one: LDL Cholesterol 172 mg/dL <100 H"),
				List.of("Page two: HDL Cholesterol 38 mg/dL >40 L",
						"Page two: Triglycerides 140 mg/dL <150"))));

		ExtractedText extracted = extractor.extract(new JobSource.PdfFile(pdf));

		assertThat(extracted.pageCount()).isEqualTo(2);
		assertThat(extracted.text()).contains("Page one").contains("Page two");
		assertThat(extracted.text()).contains("\n\n");
	}

	@Test
	@DisplayName("with OCR off, a PDF with no text layer fails UNREADABLE as it did in phase 2")
	void imageOnlyPdfIsUnreadableWithoutOcr() {
		Path pdf = pdf(TestPdfs.imageOnly());

		// The scanned-report case with nothing to fall back to: OCR switched off by
		// configuration, or no usable Tesseract on this box. UNREADABLE is the honest outcome
		// rather than an empty result, and this must keep working - it is what OCR_ENABLED=false
		// restores.
		assertThatThrownBy(() -> extractor.extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("an encrypted PDF fails UNREADABLE rather than throwing")
	void encryptedPdfIsUnreadable() {
		Path pdf = pdf(TestPdfs.encrypted());

		// InvalidPasswordException is an IOException, so this would escape as a 500 if the
		// extractor did not map it.
		assertThatThrownBy(() -> extractor.extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("a file that is not a PDF at all fails UNREADABLE")
	void corruptFileIsUnreadable() {
		Path notAPdf = TestPdfs.toTempFile(tempDir, "%PDF-1.7 and then nothing useful".getBytes());

		assertThatThrownBy(() -> extractor.extract(new JobSource.PdfFile(notAPdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("more pages than the limit fails DOCUMENT_TOO_LONG")
	void tooManyPagesIsRejected() {
		PdfTextExtractor limited = withoutOcr(TestProperties.withExtract(50, 100, 3));

		List<List<String>> pages = IntStream.rangeClosed(1, 4)
				.mapToObj(page -> List.of("Page " + page + ": Total Cholesterol 238 mg/dL <200"))
				.toList();
		Path pdf = pdf(TestPdfs.withPages(pages));

		assertThatThrownBy(() -> limited.extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.DOCUMENT_TOO_LONG);
	}

	@Test
	@DisplayName("text over the character limit fails DOCUMENT_TOO_LONG")
	void tooMuchTextIsRejected() {
		PdfTextExtractor limited = withoutOcr(TestProperties.withMaxTextChars(200));

		List<String> lines = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			lines.add("Total Cholesterol 238 mg/dL <200 High and then some more padding text");
		}
		Path pdf = pdf(TestPdfs.withText(lines));

		assertThatThrownBy(() -> limited.extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.DOCUMENT_TOO_LONG);
	}

	@Test
	@DisplayName("a mixed PDF keeps the text it has and counts the scanned pages")
	void mixedPdfContinuesWithWhatItHas() {
		// One real text page, built as a two-page document whose second page is blank, which
		// is what a page with no text layer looks like to the stripper.
		byte[] bytes = TestPdfs.withPages(List.of(
				List.of("Total Cholesterol 238 mg/dL <200 H",
						"HDL Cholesterol 38 mg/dL >40 L",
						"Triglycerides 140 mg/dL <150",
						"LDL Cholesterol 172 mg/dL <100 H"),
				List.of()));
		Path pdf = pdf(bytes);

		ExtractedText extracted = extractor.extract(new JobSource.PdfFile(pdf));

		assertThat(extracted.pageCount()).isEqualTo(2);
		// Phase 2 only counts these. Phase 4 runs OCR over exactly this set.
		assertThat(extracted.lowTextPages()).isEqualTo(1);
		assertThat(extracted.text()).contains("238").contains("172");
	}

	// --- the scanned-page fallback (LLD 6.4) ---------------------------------

	/** What a scanned page's OCR gives back: a lipid panel, read well. */
	private static final String SCAN_TEXT = """
			RIVERBEND CLINICAL LABORATORY
			Total Cholesterol   238   mg/dL   <200   H
			LDL Cholesterol   162   mg/dL   <100   H
			HDL Cholesterol   52   mg/dL   >40""";

	@Test
	@DisplayName("a scanned PDF is read by OCR and reports textSource OCR")
	void scannedPdfIsReadByOcr() {
		FakeOcrEngine engine = new FakeOcrEngine().returning(SCAN_TEXT, 91);
		Path pdf = pdf(TestPdfs.scannedPages(1));

		ExtractedText extracted = withOcr(engine).extract(new JobSource.PdfFile(pdf));

		assertThat(extracted.textSource()).isEqualTo(TextSource.OCR);
		assertThat(extracted.text()).contains("Total Cholesterol").contains("238");
		assertThat(extracted.pageCount()).isEqualTo(1);
		assertThat(extracted.lowTextPages()).isEqualTo(1);

		// The confidences travel with the text, or the low-confidence rule has nothing to work
		// from and every OCR'd value would be flagged as though it were printed.
		assertThat(extracted.confidence().isEmpty()).isFalse();
		assertThat(extracted.confidence().lowestConfidenceFor("238", line -> true))
				.hasValue(91.0);
	}

	@Test
	@DisplayName("a mixed PDF reports MIXED and keeps both halves in page order")
	void mixedPdfReportsMixed() {
		FakeOcrEngine engine = new FakeOcrEngine().returning(SCAN_TEXT, 88);
		Path pdf = pdf(TestPdfs.mixedPages(List.of(
				List.of("Page one: Glucose 99 mg/dL 70-100",
						"Page one: Sodium 139 mmol/L 135-145")), 1));

		ExtractedText extracted = withOcr(engine).extract(new JobSource.PdfFile(pdf));

		assertThat(extracted.textSource()).isEqualTo(TextSource.MIXED);
		assertThat(extracted.pageCount()).isEqualTo(2);

		// Order matters: the OCR'd page's text has to land where that page was, not at the end.
		String text = extracted.text();
		assertThat(text).contains("Page one: Glucose").contains("Total Cholesterol");
		assertThat(text.indexOf("Page one: Glucose")).isLessThan(text.indexOf("Total Cholesterol"));
	}

	@Test
	@DisplayName("a text-layer PDF is never handed to OCR")
	void textLayerPdfSkipsOcr() {
		FakeOcrEngine engine = new FakeOcrEngine().returning(SCAN_TEXT, 91);
		Path pdf = pdf(TestPdfs.withText(List.of(
				"Total Cholesterol 238 mg/dL <200 H",
				"HDL Cholesterol 38 mg/dL >40 L",
				"Triglycerides 140 mg/dL <150",
				"LDL Cholesterol 172 mg/dL <100 H")));

		ExtractedText extracted = withOcr(engine).extract(new JobSource.PdfFile(pdf));

		assertThat(extracted.textSource()).isEqualTo(TextSource.TEXT_LAYER);
		assertThat(extracted.confidence().isEmpty()).isTrue();
		// Not an optimisation: OCR is the slowest, most memory-hungry thing the box does, and a
		// readable PDF has no reason to pay for it.
		assertThat(engine.callCount()).isZero();
	}

	@Test
	@DisplayName("more scanned pages than the OCR cap fails DOCUMENT_TOO_LONG, before rendering")
	void tooManyScannedPagesIsRejected() {
		FakeOcrEngine engine = new FakeOcrEngine().returning(SCAN_TEXT, 91);
		MediScanProperties properties =
				TestProperties.withOcrLimits(2, Duration.ofSeconds(45), Duration.ofSeconds(150));
		Path pdf = pdf(TestPdfs.scannedPages(3));

		assertThatThrownBy(() -> withOcr(engine, properties).extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.DOCUMENT_TOO_LONG);

		// The point of the cap is not to spend the time. Three pages at a 45-second page timeout
		// cannot fit a 150-second budget, so discovering that page by page would waste two
		// minutes before failing.
		assertThat(engine.callCount()).isZero();
	}

	@Test
	@DisplayName("a page read below the page-confidence floor counts as empty")
	void lowConfidencePageCountsAsEmpty() {
		// 12 is well under the floor of 30: this is Tesseract finding shapes in a blank scan,
		// and feeding it to the model would turn an unreadable page into invented results.
		FakeOcrEngine engine = new FakeOcrEngine().returning(SCAN_TEXT, 12);
		Path pdf = pdf(TestPdfs.scannedPages(1));

		assertThatThrownBy(() -> withOcr(engine).extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("a mixed PDF whose scan reads badly keeps its text page and stays TEXT_LAYER")
	void mixedPdfSurvivesAnUnusableScan() {
		FakeOcrEngine engine = new FakeOcrEngine().returning(SCAN_TEXT, 9);
		Path pdf = pdf(TestPdfs.mixedPages(List.of(
				List.of("Total Cholesterol 238 mg/dL <200 H",
						"HDL Cholesterol 38 mg/dL >40 L",
						"Triglycerides 140 mg/dL <150",
						"LDL Cholesterol 172 mg/dL <100 H")), 1));

		ExtractedText extracted = withOcr(engine).extract(new JobSource.PdfFile(pdf));

		// One bad page does not fail a report that has a readable page. Nothing came from OCR,
		// so nothing asks the reader to double-check an OCR'd number.
		assertThat(extracted.textSource()).isEqualTo(TextSource.TEXT_LAYER);
		assertThat(extracted.text()).contains("238");
		assertThat(extracted.confidence().isEmpty()).isTrue();
	}

	@Test
	@DisplayName("a page OCR found no words on counts as empty too")
	void emptyOcrPageCountsAsEmpty() {
		FakeOcrEngine engine = new FakeOcrEngine().returning(OcrPage.EMPTY);
		Path pdf = pdf(TestPdfs.scannedPages(1));

		assertThatThrownBy(() -> withOcr(engine).extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("a blank scanned page does not make a fully OCR'd report MIXED")
	void blankScannedPageDoesNotForceMixed() {
		// Two scans, the second blank - a trailing page from a flatbed, which is common. The
		// reader's notice should say the report was read from a scan, which it was.
		FakeOcrEngine engine = new FakeOcrEngine()
				.returning(SCAN_TEXT, 90)
				.returning(OcrPage.EMPTY);
		Path pdf = pdf(TestPdfs.scannedPages(2));

		ExtractedText extracted = withOcr(engine).extract(new JobSource.PdfFile(pdf));

		assertThat(extracted.textSource()).isEqualTo(TextSource.OCR);
		assertThat(engine.callCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("the engine is given a rendered page file, which is deleted afterwards")
	void rendersToATempFileAndCleansUp() {
		FakeOcrEngine engine = new FakeOcrEngine().returning(SCAN_TEXT, 91);
		Path pdf = pdf(TestPdfs.scannedPages(1));

		withOcr(engine).extract(new JobSource.PdfFile(pdf));

		Path rendered = engine.calls().getFirst().image();
		assertThat(rendered.getFileName().toString()).startsWith("ocr-page-").endsWith(".png");
		// Rule 2: nothing an upload produced outlives its job.
		assertThat(rendered).doesNotExist();
		assertThat(tempDir).isDirectoryContaining(file -> file.equals(pdf));
	}

	@Test
	@DisplayName("a rendered page is deleted even when OCR fails")
	void cleansUpAfterAFailedPage() {
		FakeOcrEngine engine = new FakeOcrEngine()
				.failing(OcrFailedException.Reason.TIMEOUT);
		Path pdf = pdf(TestPdfs.scannedPages(1));

		assertThatThrownBy(() -> withOcr(engine).extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class);

		assertThat(engine.calls().getFirst().image()).doesNotExist();
	}

	@Test
	@DisplayName("a page Tesseract refuses is UNREADABLE; a timeout or flood is EXTRACTION_FAILED")
	void mapsOcrFailuresToReportCodes() {
		// No new failure codes in this phase (O6). The split matters to the user: one says the
		// file will never work, the other says try again.
		assertThat(failureCodeFor(OcrFailedException.Reason.EXIT_CODE))
				.isEqualTo(ReportErrorCode.UNREADABLE);
		assertThat(failureCodeFor(OcrFailedException.Reason.TIMEOUT))
				.isEqualTo(ReportErrorCode.EXTRACTION_FAILED);
		assertThat(failureCodeFor(OcrFailedException.Reason.OUTPUT_CAP))
				.isEqualTo(ReportErrorCode.EXTRACTION_FAILED);
		assertThat(failureCodeFor(OcrFailedException.Reason.BUDGET_EXHAUSTED))
				.isEqualTo(ReportErrorCode.EXTRACTION_FAILED);
		assertThat(failureCodeFor(OcrFailedException.Reason.START_FAILED))
				.isEqualTo(ReportErrorCode.EXTRACTION_FAILED);
	}

	private ReportErrorCode failureCodeFor(OcrFailedException.Reason reason) {
		FakeOcrEngine engine = new FakeOcrEngine().failing(reason);
		Path pdf = pdf(TestPdfs.scannedPages(1));

		try {
			withOcr(engine).extract(new JobSource.PdfFile(pdf));
			throw new AssertionError("expected a ReportFailure for " + reason);
		}
		catch (ReportFailure failure) {
			return failure.code();
		}
	}

	@Test
	@DisplayName("an exhausted OCR budget stops the job rather than truncating the report")
	void exhaustedBudgetFails() {
		// A zero budget is the shape of a queue that ate the whole allowance. Returning the
		// pages read so far would hand the reader a silently incomplete report.
		FakeOcrEngine engine = new FakeOcrEngine().returning(SCAN_TEXT, 91);
		MediScanProperties properties =
				TestProperties.withOcrLimits(10, Duration.ofSeconds(45), Duration.ZERO);
		Path pdf = pdf(TestPdfs.scannedPages(2));

		assertThatThrownBy(() -> withOcr(engine, properties).extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.EXTRACTION_FAILED);

		assertThat(engine.callCount()).isZero();
	}

	@Test
	@DisplayName("the character limit is re-checked after OCR, not only on the text layer")
	void ocrTextIsSubjectToTheCharacterLimit() {
		// The text layer was empty and therefore within any limit. OCR is what makes a scanned
		// document long, so the check has to run again afterwards.
		FakeOcrEngine engine = new FakeOcrEngine().returning(SCAN_TEXT, 91);
		Path pdf = pdf(TestPdfs.scannedPages(1));

		assertThatThrownBy(() -> withOcr(engine, TestProperties.withMaxTextChars(50))
				.extract(new JobSource.PdfFile(pdf)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.DOCUMENT_TOO_LONG);
	}

	@Test
	@DisplayName("claims PDF sources only")
	void supportsPdfOnly() {
		assertThat(extractor.supports(SourceType.PDF)).isTrue();
		assertThat(extractor.supports(SourceType.IMAGE)).isFalse();
		assertThat(extractor.supports(SourceType.TEXT)).isFalse();
		assertThat(extractor.supports(SourceType.SAMPLE)).isFalse();

		assertThat(extractor.supports(new JobSource.RawText("some text"))).isFalse();
		assertThat(extractor.supports(new JobSource.Sample("cbc", "some text"))).isFalse();
	}

	@Test
	@DisplayName("ExtractedText.toString hides the text")
	void toStringHidesText() {
		Path pdf = pdf(TestPdfs.withText(List.of(
				"Patient Name: Jane Q. Roe",
				"Glucose 99 mg/dL 70-100",
				"Total Cholesterol 238 mg/dL <200 H",
				"HDL Cholesterol 38 mg/dL >40 L")));

		String printed = extractor.extract(new JobSource.PdfFile(pdf)).toString();

		assertThat(printed).contains("pageCount=1").contains("chars=");
		assertThat(printed).doesNotContain("Jane Q. Roe").doesNotContain("Glucose");
	}

	private Path pdf(byte[] bytes) {
		return TestPdfs.toTempFile(tempDir, bytes);
	}

}
