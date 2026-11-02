package dev.saq.mediscan.extract;

import java.time.Clock;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Builds the text extractors for tests, including those outside this package.
 *
 * <p>{@link PdfTextExtractor} is public but its collaborators are not: the OCR engine, the gate
 * and the scanned-page reader are package-private, because nothing outside {@code extract} has
 * any business constructing them. That leaves tests in other packages - {@code eval}, above all
 * - unable to build one at all, so the wiring lives here once instead of being loosened in
 * production code.
 */
public final class TextExtractors {

	private TextExtractors() {
	}

	/**
	 * A PDF extractor with OCR switched off: the text layer, and nothing else.
	 *
	 * <p>The right default for a test about text-layer extraction. It also keeps those tests
	 * independent of whether the machine running them has Tesseract installed, which is the
	 * difference between a suite that passes everywhere and one that passes here.
	 */
	public static PdfTextExtractor pdfTextLayerOnly(MediScanProperties properties) {
		return new PdfTextExtractor(new ScannedPageReader(new FakeOcrEngine(), properties),
				new OcrGate(properties, Clock.systemUTC()), () -> false, properties);
	}

	/**
	 * A PDF extractor that really runs Tesseract over pages with no text layer.
	 *
	 * <p>For the {@code ocr}-tagged tests and the evaluation, which measure what OCR actually
	 * reads. Everything else should use {@link #pdfTextLayerOnly} or a scripted engine: this one
	 * starts child processes and takes seconds per page.
	 */
	public static PdfTextExtractor pdfWithRealOcr(MediScanProperties properties) {
		TesseractCliEngine engine = new TesseractCliEngine(properties);
		return new PdfTextExtractor(new ScannedPageReader(engine, properties),
				new OcrGate(properties, Clock.systemUTC()), () -> true, properties);
	}

	/**
	 * An image extractor that really runs Tesseract, for the same callers.
	 *
	 * <p>Typed as the public {@link TextExtractor} rather than {@code ImageOcrExtractor}: the
	 * class is package-private, so a caller outside this package could not name it even to hold
	 * the result. The interface is all any caller needs.
	 */
	public static TextExtractor imageWithRealOcr(MediScanProperties properties) {
		return new ImageOcrExtractor(new ImagePreparer(properties),
				new TesseractCliEngine(properties),
				new OcrGate(properties, Clock.systemUTC()), () -> true, properties);
	}

	/**
	 * Runs the image preparer alone, for tests that need to see what Tesseract would be given.
	 *
	 * @return the prepared PNG, which the caller must delete
	 */
	public static java.nio.file.Path prepareImage(MediScanProperties properties,
			java.nio.file.Path source, java.nio.file.Path directory) {

		return new ImagePreparer(properties).prepare(source, directory);
	}
}
