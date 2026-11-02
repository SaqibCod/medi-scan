package dev.saq.mediscan.extract;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Reads the text off one prepared image.
 *
 * <p>An interface with one real implementation, so tests can script OCR output - including
 * confidences, timeouts and failures - without running a program. The low-confidence rule is
 * the most important logic in this phase and the hardest to provoke with a real OCR run, since
 * you cannot ask Tesseract to be 62% sure about a specific digit on demand.
 *
 * <p>The image handed in is already prepared: correctly rotated, grayscale, and scaled into the
 * range Tesseract reads best. An engine does not prepare images.
 */
interface OcrEngine {

	/**
	 * Reads {@code image}.
	 *
	 * @param image a prepared image file
	 * @param timeout how long this page may take before the attempt is abandoned
	 * @return what was read, which may be an empty page if the image held no text
	 * @throws OcrFailedException if the page could not be read at all
	 */
	OcrPage recognize(Path image, Duration timeout);
}
