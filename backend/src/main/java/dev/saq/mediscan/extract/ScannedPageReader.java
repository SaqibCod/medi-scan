package dev.saq.mediscan.extract;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Renders one page of a scanned PDF and reads it with OCR.
 *
 * <p>A page with no text layer is a picture of a report, so it takes the same path a photo does:
 * render it, hand the file to Tesseract, take back lines and confidences. The difference is that
 * nothing needs preparing - {@link ImagePreparer} exists to tame an arbitrary camera upload, and
 * a page rendered here is grayscale by construction and sized by the DPI we chose
 * ({@code docs/phase-4-lld.md} 6.4).
 *
 * <p><strong>Why 300 DPI.</strong> It is the figure {@link TesseractCliEngine} passes as
 * {@code --dpi}, and the two have to agree: a PNG carries no resolution metadata, so Tesseract
 * believes what it is told, and segmenting a 300 DPI render as though it were 150 reads a lab
 * table badly. At 300 DPI an A4 page is about 2480 x 3508, which sits inside the long-edge band
 * Tesseract reads best, so no rescaling is needed either.
 *
 * <p><strong>Memory.</strong> One page at a time, and the raster is released as soon as the PNG
 * is written. An A4 page at 300 DPI is roughly 8.7 megapixels, so about 8.7 MB in grayscale -
 * affordable once, and the reason this never renders a whole document up front.
 */
@Component
class ScannedPageReader {

	private static final Logger log = LoggerFactory.getLogger(ScannedPageReader.class);

	/** Matches the {@code --dpi} {@link TesseractCliEngine} passes. The two must agree. */
	private static final int RENDER_DPI = 300;

	private final OcrEngine engine;
	private final double minPageConfidence;

	ScannedPageReader(OcrEngine engine, MediScanProperties properties) {
		this.engine = engine;
		this.minPageConfidence = properties.ocr().minPageConfidence();
	}

	/**
	 * Renders and reads one page.
	 *
	 * @param renderer the renderer for the open document
	 * @param pageIndex the zero-based page index, as PDFBox numbers them
	 * @param directory where the rendered PNG may be written; it is deleted before this returns
	 * @param session the report's OCR budget, which the recognition runs under
	 * @return what OCR made of the page, or {@link OcrPage#EMPTY} when it read the page too
	 *     poorly to be worth believing. An empty page is not an error: the document may still
	 *     have enough text elsewhere, and this page simply contributes none
	 * @throws OcrFailedException if the page could not be read at all
	 * @throws IOException if rendering or writing the PNG failed
	 */
	OcrPage read(PDFRenderer renderer, int pageIndex, Path directory, OcrGate.Session session)
			throws IOException {

		Path png = renderToPng(renderer, pageIndex, directory);
		try {
			OcrPage page = session.runPage(timeout -> engine.recognize(png, timeout));

			if (page.isEmpty() || page.meanConfidence() < minPageConfidence) {
				// Below the floor this is not text, it is Tesseract finding shapes in noise.
				// Feeding it to the model would turn a blank scan into invented results, so the
				// page contributes nothing and the length checks decide the report's fate.
				log.info("scanned page unusable: page={} words={} meanConfidence={}",
						pageIndex + 1, page.wordCount(), Math.round(page.meanConfidence()));
				return OcrPage.EMPTY;
			}

			log.info("scanned page read: page={} lines={} words={} meanConfidence={}",
					pageIndex + 1, page.lines().size(), page.wordCount(),
					Math.round(page.meanConfidence()));
			return page;
		}
		finally {
			deleteQuietly(png);
		}
	}

	/** Renders a page to a grayscale PNG beside the upload, leaving nothing behind on failure. */
	private Path renderToPng(PDFRenderer renderer, int pageIndex, Path directory)
			throws IOException {

		BufferedImage image = renderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.GRAY);
		Path target = null;
		try {
			target = Files.createTempFile(directory, "ocr-page-", ".png");
			ImageIO.write(image, "png", target.toFile());
			return target;
		}
		catch (IOException | RuntimeException ex) {
			// The caller's finally only covers a file it was given, so a write that failed
			// halfway has to clean up after itself here.
			deleteQuietly(target);
			throw ex;
		}
		finally {
			// Released as soon as the bytes are on disk, before Tesseract is even started. The
			// raster and the child process must not be resident at the same time.
			image.flush();
		}
	}

	private static void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			// The retention sweep picks up whatever is left. Never the path in the log.
			log.warn("could not delete a rendered page: {}", ex.getClass().getSimpleName());
		}
	}
}
