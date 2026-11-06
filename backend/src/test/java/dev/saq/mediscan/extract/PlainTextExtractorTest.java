package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.support.TestProperties;

/**
 * Normalisation and the length limits for pasted text and samples ({@code LLD} 9.2).
 *
 * <p>The assertions that matter most are the negative ones: this stage must not alter a
 * value, a unit or a reference range. Masking's integrity check compares the numbers on
 * result rows before and after masking, and a normaliser that "tidied" a column layout would
 * break both that check and the result the patient sees.
 */
class PlainTextExtractorTest {

	/**
	 * Production limits, for the length assertions.
	 */
	private final PlainTextExtractor extractor = new PlainTextExtractor(TestProperties.defaults());

	/**
	 * A 10-character minimum, for the normalisation assertions.
	 *
	 * <p>Those tests are about what the normaliser does to a string, and padding every input
	 * out to the production 100-character minimum would bury the one line each is actually
	 * checking. The real minimum is asserted by {@link #tooShortIsUnreadable()}.
	 */
	private final PlainTextExtractor normalising =
			new PlainTextExtractor(TestProperties.withExtract(50, 10, 50));

	@Test
	@DisplayName("normalises CRLF and lone CR to newlines")
	void normalisesLineEndings() {
		String text = extract("Total Cholesterol 238 mg/dL\r\nHDL 38 mg/dL\rLDL 172 mg/dL");

		assertThat(text).doesNotContain("\r");
		assertThat(text.lines()).containsExactly(
				"Total Cholesterol 238 mg/dL",
				"HDL 38 mg/dL",
				"LDL 172 mg/dL");
	}

	@Test
	@DisplayName("replaces tabs with single spaces")
	void replacesTabs() {
		String text = extract("Total Cholesterol\t238\tmg/dL\t<200\nHDL Cholesterol 38 mg/dL >40");

		assertThat(text).doesNotContain("\t");
		assertThat(text).contains("Total Cholesterol 238 mg/dL <200");
	}

	@Test
	@DisplayName("collapses runs of blank lines to one")
	void collapsesBlankLines() {
		String text = extract("Total Cholesterol 238 mg/dL\n\n\n\n\nHDL Cholesterol 38 mg/dL >40");

		assertThat(text).isEqualTo("Total Cholesterol 238 mg/dL\n\nHDL Cholesterol 38 mg/dL >40");
	}

	@Test
	@DisplayName("strips control characters but keeps newlines")
	void stripsControlCharacters() {
		// NUL, BEL and a vertical tab: PDF extraction and clipboard paste both emit these.
		// Built from char values rather than written as unicode escapes, because javac expands
		// those before lexing, so the escape would already be a control character in the file.
		String raw = "Total Cholesterol" + (char) 0 + " 238 mg/dL" + (char) 7
				+ " <200\nHDL 38" + (char) 11 + " mg/dL >40 L";

		String text = extract(raw);

		assertThat(text).doesNotContain(String.valueOf((char) 0))
				.doesNotContain(String.valueOf((char) 7))
				.doesNotContain(String.valueOf((char) 11));
		// The values either side of a stripped character have to be untouched.
		assertThat(text).contains("238").contains("<200").contains(">40");
		assertThat(text.lines()).hasSize(2);
	}

	@Test
	@DisplayName("keeps the column gaps that separate a test name from its value")
	void preservesColumnSpacing() {
		String row = "Total Cholesterol           238         mg/dL       <200           H";

		String text = extract(row + "\nHDL Cholesterol 38 mg/dL >40 L");

		// Deliberately not collapsed. These runs of spaces are the column layout, and they
		// are how a result row stays readable as a row.
		assertThat(text).contains(row);
	}

	@Test
	@DisplayName("text under the minimum fails UNREADABLE")
	void tooShortIsUnreadable() {
		// The production minimum, on the production-configured extractor.
		assertThatThrownBy(() -> extractor.extract(new JobSource.RawText("Glucose 99 mg/dL")))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("text over the maximum fails DOCUMENT_TOO_LONG")
	void tooLongIsRejected() {
		PlainTextExtractor limited = new PlainTextExtractor(TestProperties.withMaxTextChars(200));

		String long_ = "Total Cholesterol 238 mg/dL <200 H\n".repeat(50);

		assertThatThrownBy(() -> limited.extract(new JobSource.RawText(long_)))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.DOCUMENT_TOO_LONG);
	}

	@Test
	@DisplayName("a sample is extracted the same way as pasted text")
	void handlesSamples() {
		String sampleText = "Patient Name: Jane Q. Roe\r\nTotal Cholesterol 238 mg/dL <200 H";

		ExtractedText extracted = normalising.extract(new JobSource.Sample("lipid-panel", sampleText));

		assertThat(extracted.pageCount()).isEqualTo(1);
		assertThat(extracted.lowTextPages()).isZero();
		assertThat(extracted.text()).doesNotContain("\r").contains("238");
	}

	@Test
	@DisplayName("a sample is still length-checked, having skipped the upload check")
	void samplesAreLengthChecked() {
		// Pasted text was bounded at upload; a sample never passed through that check, so the
		// limit is enforced here rather than trusted from upstream.
		assertThatThrownBy(() -> extractor.extract(new JobSource.Sample("tiny", "Glucose 99")))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("claims text and sample sources only")
	void supportsTextSources() {
		assertThat(extractor.supports(SourceType.TEXT)).isTrue();
		assertThat(extractor.supports(SourceType.SAMPLE)).isTrue();
		assertThat(extractor.supports(SourceType.PDF)).isFalse();
		assertThat(extractor.supports(SourceType.IMAGE)).isFalse();
	}

	@Test
	@DisplayName("JobSource.toString hides the text it carries")
	void jobSourceToStringHidesText() {
		String secret = "Patient Name: Jane Q. Roe";

		assertThat(new JobSource.RawText(secret).toString())
				.contains("length=").doesNotContain("Jane Q. Roe");
		assertThat(new JobSource.Sample("lipid-panel", secret).toString())
				.contains("lipid-panel").doesNotContain("Jane Q. Roe");
	}

	private String extract(String raw) {
		return normalising.extract(new JobSource.RawText(raw)).text();
	}
}
