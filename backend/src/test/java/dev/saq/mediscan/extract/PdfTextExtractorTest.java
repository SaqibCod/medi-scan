package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;
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

	private final PdfTextExtractor extractor = new PdfTextExtractor(TestProperties.defaults());

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
	@DisplayName("a PDF with no text layer fails UNREADABLE")
	void imageOnlyPdfIsUnreadable() {
		Path pdf = pdf(TestPdfs.imageOnly());

		// This is the scanned-report case. Until phase 4 adds OCR there is nothing to fall
		// back to, so UNREADABLE is the honest outcome rather than an empty result.
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
		PdfTextExtractor limited = new PdfTextExtractor(TestProperties.withExtract(50, 100, 3));

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
		PdfTextExtractor limited = new PdfTextExtractor(TestProperties.withMaxTextChars(200));

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
