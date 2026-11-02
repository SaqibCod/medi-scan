package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.support.LogCapture;
import dev.saq.mediscan.support.TestProperties;

/**
 * {@link OcrStartupCheck}: does the box have a usable Tesseract, and what happens when it does
 * not.
 *
 * <p>The two behaviours are the point. In production a missing Tesseract means the image was
 * built wrong, and serving traffic that is going to fail is worse than refusing to start. On a
 * developer's machine it means they have not installed it, and the rest of the application
 * should still run.
 */
class OcrStartupCheckTest {

	private static final String MISSING = "definitely-not-a-program-on-this-box";

	@Test
	@DisplayName("OCR disabled by configuration reports unavailable without probing anything")
	void disabledByConfiguration() {
		// The command is deliberately nonsense: if the check probed it anyway, this would
		// either throw or warn, and it must do neither.
		MediScanProperties properties = TestProperties.withOcr(
				disabled(TestProperties.withOcrCommand(MISSING).ocr()));

		OcrStartupCheck check = new OcrStartupCheck(properties, true);

		assertThat(check.available()).isFalse();
		assertThat(check.version()).isNull();
	}

	@Test
	@DisplayName("a missing program fails startup when OCR is required")
	void missingProgramFailsStartupWhenRequired() {
		MediScanProperties properties = TestProperties.withOcrCommand(MISSING);

		// prod. The image ships Tesseract, so its absence is a broken deployment.
		assertThatThrownBy(() -> new OcrStartupCheck(properties, true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(MISSING)
				.hasMessageContaining("OCR_ENABLED=false");
	}

	@Test
	@DisplayName("a missing program warns and turns OCR off when it is not required")
	void missingProgramWarnsWhenOptional() {
		MediScanProperties properties = TestProperties.withOcrCommand(MISSING);

		try (LogCapture logs = LogCapture.start()) {
			OcrStartupCheck check = new OcrStartupCheck(properties, false);

			assertThat(check.available()).isFalse();
			assertThat(check.version()).isNull();
			assertThat(logs.allText())
					.contains("OCR off")
					.contains("415");
		}
	}

	@Test
	@DisplayName("a real Tesseract is detected, and its version is logged")
	void detectsRealTesseract() {
		// Skipped rather than failed where Tesseract is not installed: this is the one test
		// here that needs the real thing, and the suite has to pass without it.
		OcrStartupCheck probe = new OcrStartupCheck(TestProperties.defaults(), false);
		org.junit.jupiter.api.Assumptions.assumeTrue(probe.available(),
				"tesseract is not on the PATH, so there is nothing to detect");

		try (LogCapture logs = LogCapture.start()) {
			OcrStartupCheck check = new OcrStartupCheck(TestProperties.defaults(), true);

			assertThat(check.available()).isTrue();
			// "tesseract 5.4.0" locally, "tesseract 5.3.4" in the runtime image.
			assertThat(check.version()).startsWith("tesseract ");
			assertThat(logs.allText())
					.contains("OCR available")
					.contains(check.version());
		}
	}

	private static MediScanProperties.Ocr disabled(MediScanProperties.Ocr ocr) {
		return new MediScanProperties.Ocr(false, ocr.command(), ocr.tessdataDir(), ocr.psm(),
				ocr.threads(), ocr.pageTimeout(), ocr.totalTimeout(), ocr.maxPages(),
				ocr.maxPixels(), ocr.maxDecodedBytes(), ocr.minLongEdge(), ocr.maxLongEdge(),
				ocr.maxOutputBytes(), ocr.minPageConfidence(), ocr.minValueConfidence());
	}
}
