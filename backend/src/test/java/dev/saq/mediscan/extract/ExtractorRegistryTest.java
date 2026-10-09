package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.support.TestProperties;

/**
 * Extractor dispatch, and the phase 2 refusal of images.
 *
 * <p>The image case is the interesting one. Nothing anywhere says "reject PNG": images are
 * refused because no registered extractor claims {@code IMAGE}. These tests pin that, because
 * it is also the mechanism by which phase 4 turns images on - by adding a bean, with nothing
 * here to remember to change.
 */
class ExtractorRegistryTest {

	private final ExtractorRegistry registry = new ExtractorRegistry(List.of(
			new PdfTextExtractor(TestProperties.defaults()),
			new PlainTextExtractor(TestProperties.defaults())));

	@Test
	@DisplayName("supports the three source types phase 2 accepts")
	void supportsPhase2Types() {
		assertThat(registry.supports(SourceType.PDF)).isTrue();
		assertThat(registry.supports(SourceType.TEXT)).isTrue();
		assertThat(registry.supports(SourceType.SAMPLE)).isTrue();
	}

	@Test
	@DisplayName("does not support images, which is how a PNG upload becomes a 415")
	void doesNotSupportImages() {
		// The upload validator asks exactly this question before writing a file, and turns a
		// false into 415 UNSUPPORTED_FILE_TYPE (contract section 4.1).
		assertThat(registry.supports(SourceType.IMAGE)).isFalse();
	}

	@Test
	@DisplayName("routes text and samples to the plain extractor")
	void routesTextSources() {
		String text = """
				Total Cholesterol 238 mg/dL <200 H
				HDL Cholesterol 38 mg/dL >40 L
				LDL Cholesterol 172 mg/dL <100 H
				Triglycerides 140 mg/dL <150
				""";

		assertThat(registry.extract(new JobSource.RawText(text)).text()).contains("238");
		assertThat(registry.extract(new JobSource.Sample("lipid-panel", text)).text()).contains("238");
	}

	@Test
	@DisplayName("a source nothing can read fails UNREADABLE rather than escaping")
	void unreadableSourceFails() {
		// Only reachable if a source reaches a job that upload should have refused - a bug,
		// but one that still has to surface as a report failure rather than a 500.
		ExtractorRegistry empty = new ExtractorRegistry(List.of());

		assertThatThrownBy(() -> empty.extract(new JobSource.PdfFile(Path.of("nope.pdf"))))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}
}
