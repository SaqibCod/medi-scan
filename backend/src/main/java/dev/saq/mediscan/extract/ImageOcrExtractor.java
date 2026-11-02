package dev.saq.mediscan.extract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.report.TextSource;

/**
 * Reads an uploaded photo: prepare it, OCR it, hand back the text and the confidences.
 *
 * <p>Registering this as a {@link TextExtractor} is the entire upload-layer change for images.
 * {@code UploadValidator} asks {@code ExtractorRegistry} whether anything can read the sniffed
 * type, so a bean that claims {@code IMAGE} turns the phase-2 {@code 415} into an accepted
 * upload, and a bean that declines to claim it turns it back again. Nothing in the controller or
 * the validator knows about OCR ({@code docs/phase-4-lld.md} 6.6).
 *
 * <p>It declines when OCR is unavailable - configuration off, or no usable Tesseract on a
 * non-production box - which is how {@code OCR_ENABLED=false} restores the pre-phase-4
 * behaviour exactly, rather than accepting an upload it cannot process.
 *
 * <p>The prepared PNG is deleted in a {@code finally}, including when OCR fails. The uploaded
 * file itself is the job's to delete, as it already was.
 */
@Component
class ImageOcrExtractor implements TextExtractor {

	private static final Logger log = LoggerFactory.getLogger(ImageOcrExtractor.class);

	private final ImagePreparer preparer;
	private final OcrEngine engine;
	private final OcrGate gate;
	private final OcrAvailability ocr;
	private final double minPageConfidence;
	private final int minTotalChars;
	private final int maxTextChars;

	ImageOcrExtractor(ImagePreparer preparer, OcrEngine engine, OcrGate gate,
			OcrAvailability ocr, MediScanProperties properties) {

		this.preparer = preparer;
		this.engine = engine;
		this.gate = gate;
		this.ocr = ocr;
		this.minPageConfidence = properties.ocr().minPageConfidence();
		this.minTotalChars = properties.extract().minTotalChars();
		this.maxTextChars = properties.upload().maxTextChars();
	}

	@Override
	public boolean supports(JobSource source) {
		return source instanceof JobSource.ImageFile && ocr.available();
	}

	@Override
	public boolean supports(SourceType sourceType) {
		return sourceType == SourceType.IMAGE && ocr.available();
	}

	@Override
	public ExtractedText extract(JobSource source) {
		if (!(source instanceof JobSource.ImageFile(Path path, JobSource.ImageKind kind))) {
			throw new IllegalArgumentException(
					"ImageOcrExtractor cannot read " + source.getClass().getSimpleName());
		}

		Path prepared = preparer.prepare(path, path.getParent());
		try {
			OcrPage page = readPage(prepared);

			if (page.isEmpty() || page.meanConfidence() < minPageConfidence) {
				// Either nothing was found, or what was found is not worth believing. Feeding
				// garbage to the model would turn an unreadable photo into invented results.
				log.info("image OCR produced nothing usable: words={} meanConfidence={}",
						page.wordCount(), Math.round(page.meanConfidence()));
				throw new ReportFailure(ReportErrorCode.UNREADABLE);
			}

			String text = TextNormalizer.normalize(page.text());
			if (text.length() < minTotalChars) {
				throw new ReportFailure(ReportErrorCode.UNREADABLE);
			}
			if (text.length() > maxTextChars) {
				throw new ReportFailure(ReportErrorCode.DOCUMENT_TOO_LONG);
			}

			log.info("image extracted: kind={} chars={} words={} meanConfidence={}",
					kind, text.length(), page.wordCount(), Math.round(page.meanConfidence()));

			return new ExtractedText(text, 1, 1, TextSource.OCR,
					ConfidenceIndex.of(List.of(page)));
		}
		finally {
			deleteQuietly(prepared);
		}
	}

	/** One page, under the gate, with OCR failures mapped to report failures. */
	private OcrPage readPage(Path prepared) {
		try {
			return gate.open().runPage(timeout -> engine.recognize(prepared, timeout));
		}
		catch (OcrFailedException failure) {
			// A photo has one page, so a page that cannot be read is a report that cannot be
			// read. Which code depends on why: a timeout or a flood is a processing failure,
			// while an unreadable image is just unreadable (LLD 6.2, no new failure codes).
			log.info("image OCR failed: reason={}", failure.reason());
			throw new ReportFailure(switch (failure.reason()) {
				case TIMEOUT, OUTPUT_CAP, BUDGET_EXHAUSTED, INTERRUPTED, START_FAILED ->
						ReportErrorCode.EXTRACTION_FAILED;
				case EXIT_CODE -> ReportErrorCode.UNREADABLE;
			});
		}
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			// The retention sweep picks up anything left behind. Never the path in the log.
			log.warn("could not delete a prepared image: {}", ex.getClass().getSimpleName());
		}
	}
}
