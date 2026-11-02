package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dev.saq.mediscan.extract.ExifProbe;

/**
 * {@link OcrCaseRenderer}: does the synthetic input really have the property it claims?
 *
 * <p>This matters more than it looks. Every OCR number in the evaluation is measured against
 * these files, so a variant that quietly fails to be what it says - a "scanned" PDF that still
 * has a text layer, a "rotated" JPEG with no EXIF tag - would produce a run that passes while
 * testing nothing. These tests are the only thing standing between a green gate and a
 * meaningless one.
 *
 * <p>No Tesseract needed: this is about the files, not about reading them.
 */
class OcrCaseRendererTest {

	private static final String REPORT = """
			RIVERBEND CLINICAL LABORATORY
			Patient Name: Monica Alvarez
			Collected: 09/15/2026
			TEST                  RESULT    UNIT     REFERENCE
			Total Cholesterol     238       mg/dL    <200      H
			HDL Cholesterol       38        mg/dL    >40       L
			LDL Cholesterol       162       mg/dL    <100      H
			Triglycerides         140       mg/dL    <150
			""";

	private static BufferedImage decode(byte[] bytes) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		assertThat(image).as("the bytes must decode as an image").isNotNull();
		return image;
	}

	@ParameterizedTest
	@EnumSource(OcrCaseRenderer.Variant.class)
	@DisplayName("every variant produces readable bytes of the kind it declares")
	void everyVariantProducesItsKind(OcrCaseRenderer.Variant variant) throws IOException {
		OcrCaseRenderer.Rendered rendered = OcrCaseRenderer.render(REPORT, variant,
				OcrCaseRenderer.Typeface.MONOSPACED, 7391);

		assertThat(rendered.kind()).isEqualTo(variant.kind());
		assertThat(rendered.bytes()).isNotEmpty();

		switch (variant.kind()) {
			case PNG -> {
				assertThat(rendered.bytes()[0]).isEqualTo((byte) 0x89);
				decode(rendered.bytes());
			}
			case JPEG -> {
				assertThat(rendered.bytes()[0]).isEqualTo((byte) 0xFF);
				assertThat(rendered.bytes()[1]).isEqualTo((byte) 0xD8);
				decode(rendered.bytes());
			}
			case PDF -> assertThat(new String(rendered.bytes(), 0, 5,
					java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
		}
	}

	@Test
	@DisplayName("a clean scan is a grayscale Letter page at 300 DPI")
	void cleanScanIsAGrayscaleLetterPage() throws IOException {
		OcrCaseRenderer.Rendered rendered = OcrCaseRenderer.render(REPORT,
				OcrCaseRenderer.Variant.CLEAN_SCAN, OcrCaseRenderer.Typeface.MONOSPACED, 1);

		BufferedImage image = decode(rendered.bytes());
		assertThat(image.getWidth()).isEqualTo(2550);
		assertThat(image.getHeight()).isEqualTo(3300);
		// Grayscale from the start, which is what a scanner produces and what the preparer and
		// Tesseract both want. A colour render would be measuring a conversion we never do.
		assertThat(image.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
	}

	@Test
	@DisplayName("the same seed renders the same bytes")
	void renderingIsReproducible() {
		byte[] first = OcrCaseRenderer.render(REPORT, OcrCaseRenderer.Variant.PHONE_PHOTO,
				OcrCaseRenderer.Typeface.MONOSPACED, 42).bytes();
		byte[] second = OcrCaseRenderer.render(REPORT, OcrCaseRenderer.Variant.PHONE_PHOTO,
				OcrCaseRenderer.Typeface.MONOSPACED, 42).bytes();

		// Without this, a score that moved between runs could be the pipeline or could be a
		// different photograph, and there would be no way to tell which.
		assertThat(first).isEqualTo(second);
	}

	@Test
	@DisplayName("a different seed renders a different photo")
	void seedsDiffer() {
		byte[] first = OcrCaseRenderer.render(REPORT, OcrCaseRenderer.Variant.PHONE_PHOTO,
				OcrCaseRenderer.Typeface.MONOSPACED, 1).bytes();
		byte[] second = OcrCaseRenderer.render(REPORT, OcrCaseRenderer.Variant.PHONE_PHOTO,
				OcrCaseRenderer.Typeface.MONOSPACED, 2).bytes();

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	@DisplayName("the low-res variant is full size and short of detail")
	void lowResIsUpscaled() throws IOException {
		BufferedImage lowRes = decode(OcrCaseRenderer.render(REPORT,
				OcrCaseRenderer.Variant.LOW_RES, OcrCaseRenderer.Typeface.MONOSPACED, 1).bytes());
		BufferedImage clean = decode(OcrCaseRenderer.render(REPORT,
				OcrCaseRenderer.Variant.CLEAN_SCAN, OcrCaseRenderer.Typeface.MONOSPACED, 1).bytes());

		// Same pixel size as the clean scan, which is the point: the file claims 300 DPI detail
		// it does not have, so nothing upstream can tell by looking at the dimensions.
		assertThat(lowRes.getWidth()).isEqualTo(clean.getWidth());
		assertThat(lowRes.getHeight()).isEqualTo(clean.getHeight());
	}

	@Test
	@DisplayName("the rotated variant is stored sideways, with an EXIF orientation of 6")
	void rotatedVariantIsSidewaysWithATag() throws IOException {
		byte[] bytes = OcrCaseRenderer.render(REPORT, OcrCaseRenderer.Variant.ROTATED_EXIF,
				OcrCaseRenderer.Typeface.MONOSPACED, 1).bytes();

		BufferedImage stored = decode(bytes);
		// Landscape on disk: a reader that ignores the tag hands Tesseract a sideways page.
		assertThat(stored.getWidth()).isGreaterThan(stored.getHeight());

		// And the tag really is in the file. Without it this variant is just an unreadable
		// image, and it would fail for a reason that has nothing to do with EXIF.
		assertThat(ExifProbe.orientationOf(bytes)).isEqualTo(6);
	}

	@Test
	@DisplayName("the EXIF payload is a TIFF block our own parser can read")
	void exifPayloadRoundTrips() {
		// Written from the specification on both sides; this is the test that says the two
		// agree. A mismatch here would make the rotated case silently untested.
		for (int orientation : List.of(1, 3, 6, 8)) {
			byte[] app1 = OcrCaseRenderer.exifOrientation(orientation);
			assertThat(ExifProbe.orientationFromExif(app1))
					.as("orientation %s", orientation)
					.isEqualTo(orientation);
		}
	}

	@Test
	@DisplayName("a scanned PDF has no text layer at all")
	void scannedPdfHasNoTextLayer() throws IOException {
		byte[] pdf = OcrCaseRenderer.render(REPORT, OcrCaseRenderer.Variant.SCANNED_PDF,
				OcrCaseRenderer.Typeface.MONOSPACED, 1).bytes();

		// The whole premise of the variant. If PDFBox can read a single word out of it, the
		// extractor would take the text-layer path and the OCR fallback would never run.
		assertThat(textLayerOf(pdf).strip()).isEmpty();
	}

	@Test
	@DisplayName("a mixed PDF has text on page one and nothing on page two")
	void mixedPdfHasExactlyOneTextPage() throws IOException {
		byte[] pdf = OcrCaseRenderer.render(REPORT, OcrCaseRenderer.Variant.MIXED_PDF,
				OcrCaseRenderer.Typeface.MONOSPACED, 1).bytes();

		try (PDDocument document = Loader.loadPDF(pdf)) {
			assertThat(document.getNumberOfPages()).isEqualTo(2);

			// Page one is real text: the header half of the report.
			assertThat(pageText(document, 1)).contains("RIVERBEND").contains("Monica Alvarez");

			// Page two has no text layer, and the result rows are on it - so a MIXED case's
			// values come from OCR while its header comes from the text layer. That split is
			// what makes the variant worth running: the confidence index must cover the OCR'd
			// half and leave the other half alone.
			assertThat(pageText(document, 2).strip()).isEmpty();
			assertThat(pageText(document, 1)).doesNotContain("238");
		}
	}

	@Test
	@DisplayName("a case with an explicit page break renders one page per part")
	void honoursExplicitPageBreaks() {
		String twoPages = "Page one line\n" + OcrCaseRenderer.PAGE_BREAK + "\nPage two line";

		assertThat(OcrCaseRenderer.paginate(twoPages)).hasSize(2);
		assertThat(OcrCaseRenderer.paginate(twoPages).get(1)).containsExactly("Page two line");
	}

	@Test
	@DisplayName("a report longer than a page is split rather than rendered as a tall strip")
	void paginatesLongReports() {
		String longReport = ("Total Cholesterol 238 mg/dL <200\n").repeat(200);

		List<List<String>> pages = OcrCaseRenderer.paginate(longReport);
		assertThat(pages).hasSizeGreaterThan(1);
		// A real scanner produces Letter pages. One 200-line image would also be over the long
		// edge the preparer scales to, so it would be downscaled into illegibility.
		assertThat(pages.getFirst()).hasSizeLessThanOrEqualTo(52);
	}

	private static String textLayerOf(byte[] pdf) throws IOException {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			return new PDFTextStripper().getText(document);
		}
	}

	private static String pageText(PDDocument document, int page) throws IOException {
		PDFTextStripper stripper = new PDFTextStripper();
		stripper.setStartPage(page);
		stripper.setEndPage(page);
		return stripper.getText(document);
	}
}
