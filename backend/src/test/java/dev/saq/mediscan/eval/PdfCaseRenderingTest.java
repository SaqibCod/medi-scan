package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.extract.ExtractedText;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.extract.PdfTextExtractor;
import dev.saq.mediscan.support.TestPdfs;
import dev.saq.mediscan.support.TestProperties;

/**
 * Confirms every {@code format: "PDF"} case actually survives the real {@link PdfTextExtractor}
 * - no model, no database. Per approval point 2, there is no separate {@code PdfCaseRenderer}:
 * cases are rendered directly with the same {@link TestPdfs} helper the production integration
 * tests already use.
 *
 * <p>{@code pdf-multi-page-cbc}'s {@code report.txt} marks its two pages with a literal
 * {@code "[PAGE BREAK]"} line; splitting on it here is the one piece of "rendering" logic a PDF
 * case needs.
 */
class PdfCaseRenderingTest {

	private static final String PAGE_BREAK = "[PAGE BREAK]";

	private final PdfTextExtractor extractor = new PdfTextExtractor(TestProperties.defaults());

	@TempDir
	Path tempDir;

	@Test
	void pdfLipidTableSurvivesExtraction() {
		EvalCase evalCase = load("pdf-lipid-table");
		String extracted = extract(evalCase.reportText());

		for (ExpectedResult.ExpectedBiomarker row : expectedOf("pdf-lipid-table").biomarkers()) {
			assertThat(extracted).contains(row.testName()).contains(row.rawValue());
		}
	}

	@Test
	void pdfMultiPageCbcSurvivesExtractionAcrossBothPages() {
		EvalCase evalCase = load("pdf-multi-page-cbc");
		List<String> pages = Arrays.stream(evalCase.reportText().split(Pattern.quote(PAGE_BREAK))).toList();
		assertThat(pages).as("expected a page break marker").hasSize(2);

		byte[] pdf = TestPdfs.withPages(pages.stream().map(PdfCaseRenderingTest::linesOf).toList());
		String extracted = extract(pdf);

		for (ExpectedResult.ExpectedBiomarker row : expectedOf("pdf-multi-page-cbc").biomarkers()) {
			assertThat(extracted).contains(row.testName()).contains(row.rawValue());
		}
	}

	@Test
	void pdfImageOnlyFailsUnreadable() {
		byte[] pdf = TestPdfs.imageOnly();
		Path file = TestPdfs.toTempFile(tempDir, pdf);

		assertThatThrownBy(() -> extractor.extract(new JobSource.PdfFile(file)))
				.isInstanceOf(ReportFailure.class)
				.extracting(ex -> ((ReportFailure) ex).code())
				.isEqualTo(dev.saq.mediscan.config.ReportErrorCode.UNREADABLE);
	}

	private String extract(String reportText) {
		return extract(TestPdfs.withText(linesOf(reportText)));
	}

	private String extract(byte[] pdfBytes) {
		Path file = TestPdfs.toTempFile(tempDir, pdfBytes);
		ExtractedText extracted = extractor.extract(new JobSource.PdfFile(file));
		return extracted.text();
	}

	private static List<String> linesOf(String text) {
		return Arrays.stream(text.split("\\R")).toList();
	}

	private static EvalCase load(String caseId) {
		return EvalDatasetLoader.loadAll().stream()
				.map(EvalDatasetLoader.Entry::evalCase)
				.filter(c -> c.id().equals(caseId))
				.findFirst()
				.orElseThrow();
	}

	private static ExpectedResult expectedOf(String caseId) {
		return EvalDatasetLoader.loadAll().stream()
				.filter(e -> e.evalCase().id().equals(caseId))
				.map(EvalDatasetLoader.Entry::expected)
				.findFirst()
				.orElseThrow();
	}
}
