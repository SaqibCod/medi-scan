package dev.saq.mediscan.extract;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.report.TextSource;

/**
 * Pulls text out of a PDF with PDFBox.
 *
 * <p><strong>Memory.</strong> The document is loaded with a temp-file stream cache rather than
 * into a byte array. On a 2 GB box, buffering a 10 MB PDF's decompressed streams in heap is
 * how this becomes an out-of-memory kill under two concurrent uploads
 * ({@code backend/CLAUDE.md}, "Performance and memory"). PDFBox 3 expresses that as a
 * {@code StreamCacheCreateFunction}; {@code IOUtils.createTempFileOnlyStreamCache()} is the
 * replacement for 2.x's {@code MemoryUsageSetting.setupTempFileOnly()}.
 *
 * <p><strong>Page by page.</strong> Text is taken one page at a time so pages with too little
 * text to be anything but a scan can be counted. Phase 2 only reports that count; phase 4 runs
 * OCR over exactly those pages. A document that is partly scanned still returns the text it
 * does have, rather than failing outright.
 *
 * <p><strong>The scanned-page fallback.</strong> A page with fewer than
 * {@code extract.min-chars-per-page} characters is a picture, not a page, so it is rendered and
 * read by {@link ScannedPageReader} and its text replaces that page's empty string. The page
 * order never changes, which matters because the model is told the document's reading order is
 * meaningful. Whether any page was OCR'd decides the {@code textSource} the client uses to ask
 * the reader to check their values ({@code docs/phase-4-lld.md} 6.4).
 *
 * <p><strong>When OCR is off</strong> - configuration, or no usable Tesseract on a box where
 * that is not fatal - this behaves exactly as it did in phase 2: a scanned PDF has no text and
 * ends {@code UNREADABLE}. The fallback is additive, so switching it off cannot change how a
 * text-layer PDF is read.
 *
 * <p><strong>Sorted by position.</strong> A PDF's content stream order is whatever the
 * producing software emitted, which for a lab report's table can interleave columns.
 * {@code setSortByPosition(true)} gives reading order instead, so a result row arrives as one
 * line and the extraction prompt sees a table.
 */
@Component
public class PdfTextExtractor implements TextExtractor {

	private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

	private final ScannedPageReader scannedPages;
	private final OcrGate gate;
	private final OcrAvailability ocr;
	private final int minCharsPerPage;
	private final int minTotalChars;
	private final int maxPages;
	private final int maxTextChars;
	private final int maxOcrPages;

	public PdfTextExtractor(ScannedPageReader scannedPages, OcrGate gate, OcrAvailability ocr,
			MediScanProperties properties) {

		this.scannedPages = scannedPages;
		this.gate = gate;
		this.ocr = ocr;
		this.minCharsPerPage = properties.extract().minCharsPerPage();
		this.minTotalChars = properties.extract().minTotalChars();
		this.maxPages = properties.extract().maxPages();
		this.maxTextChars = properties.upload().maxTextChars();
		this.maxOcrPages = properties.ocr().maxPages();
	}

	@Override
	public boolean supports(JobSource source) {
		return source instanceof JobSource.PdfFile;
	}

	@Override
	public boolean supports(SourceType sourceType) {
		return sourceType == SourceType.PDF;
	}

	@Override
	public ExtractedText extract(JobSource source) {
		if (!(source instanceof JobSource.PdfFile(Path path))) {
			throw new IllegalArgumentException(
					"PdfTextExtractor cannot read " + source.getClass().getSimpleName());
		}

		try (PDDocument document = Loader.loadPDF(path.toFile(), "",
				IOUtils.createTempFileOnlyStreamCache())) {

			int pageCount = document.getNumberOfPages();
			if (pageCount == 0) {
				throw new ReportFailure(ReportErrorCode.UNREADABLE);
			}
			if (pageCount > maxPages) {
				// Refused before extracting anything: the point of the page cap is to avoid
				// spending the time, not to discover afterwards that we should not have.
				throw new ReportFailure(ReportErrorCode.DOCUMENT_TOO_LONG);
			}

			return extractPages(document, pageCount, path);
		}
		catch (ReportFailure failure) {
			throw failure;
		}
		catch (IOException ex) {
			// Covers an encrypted PDF (InvalidPasswordException extends IOException), a
			// truncated file, and a file whose bytes passed the %PDF- signature check but are
			// not a parseable document. All are the same thing to the user: we cannot read it.
			//
			// The exception message is deliberately not logged - PDFBox quotes document
			// content in some parse errors (backend/CLAUDE.md, "Exception logging").
			log.info("PDF could not be read: {}", ex.getClass().getSimpleName());
			throw new ReportFailure(ReportErrorCode.UNREADABLE);
		}
	}

	private ExtractedText extractPages(PDDocument document, int pageCount, Path source)
			throws IOException {

		List<String> pageTexts = readTextLayer(document, pageCount);
		List<Integer> lowTextPages = lowTextPages(pageTexts);

		if (lowTextPages.isEmpty() || !ocr.available()) {
			// Nothing to OCR, or nothing to OCR with. Either way this is the phase-2 path
			// exactly: whatever the text layer gave, and UNREADABLE if that was not enough.
			return finish(pageTexts, pageCount, lowTextPages.size(), Set.of(), List.of());
		}

		if (lowTextPages.size() > maxOcrPages) {
			// Refused before rendering anything. Ten scanned pages is already 450 seconds of
			// worst-case page timeouts against a 150-second budget, so a thicker document is
			// not a long report, it is one this box cannot read (O6, no new failure codes).
			log.info("too many scanned pages to read: lowTextPages={} max={}",
					lowTextPages.size(), maxOcrPages);
			throw new ReportFailure(ReportErrorCode.DOCUMENT_TOO_LONG);
		}

		return ocrLowTextPages(document, pageCount, pageTexts, lowTextPages, source);
	}

	/** Each page's text layer, in page order, stripped. Empty strings for scanned pages. */
	private List<String> readTextLayer(PDDocument document, int pageCount) throws IOException {
		PDFTextStripper stripper = new PDFTextStripper();
		stripper.setSortByPosition(true);

		List<String> pageTexts = new ArrayList<>(pageCount);
		int total = 0;

		for (int page = 1; page <= pageCount; page++) {
			stripper.setStartPage(page);
			stripper.setEndPage(page);

			String pageText = stripper.getText(document).strip();
			pageTexts.add(pageText);

			// Checked inside the loop so a pathological PDF cannot build a 200 MB string
			// before the limit is noticed.
			total += pageText.length();
			if (total > maxTextChars) {
				throw new ReportFailure(ReportErrorCode.DOCUMENT_TOO_LONG);
			}
		}

		return pageTexts;
	}

	/** The indexes of the pages with too little text to be anything but a scan. */
	private List<Integer> lowTextPages(List<String> pageTexts) {
		List<Integer> low = new ArrayList<>();
		for (int index = 0; index < pageTexts.size(); index++) {
			if (pageTexts.get(index).length() < minCharsPerPage) {
				low.add(index);
			}
		}
		return low;
	}

	/**
	 * Reads the scanned pages and puts their text where the empty pages were.
	 *
	 * <p>A page that cannot be read does not fail the report on its own: a mixed document whose
	 * one scanned page is unreadable still has its other pages, and the length checks at the end
	 * decide whether what is left is a report. An exhausted OCR budget is different - it means
	 * the remaining pages cannot be read in the time the client is waiting, so the job stops
	 * rather than pretending the document ends there.
	 */
	private ExtractedText ocrLowTextPages(PDDocument document, int pageCount,
			List<String> pageTexts, List<Integer> lowTextPages, Path source) throws IOException {

		PDFRenderer renderer = new PDFRenderer(document);
		OcrGate.Session session = gate.open();
		Path directory = source.getParent();

		List<OcrPage> read = new ArrayList<>();
		Set<Integer> ocrPages = new LinkedHashSet<>();

		for (int index : lowTextPages) {
			OcrPage page;
			try {
				page = scannedPages.read(renderer, index, directory, session);
			}
			catch (OcrFailedException failure) {
				throw failureFor(failure);
			}

			if (page.isEmpty()) {
				continue;
			}

			pageTexts.set(index, page.text());
			read.add(page);
			ocrPages.add(index);
		}

		session.logSummary();

		return finish(pageTexts, pageCount, lowTextPages.size(), ocrPages, read);
	}

	/**
	 * Which report failure an OCR failure is.
	 *
	 * <p>No new codes, as O6 requires. A page Tesseract refused outright is an unreadable
	 * document; a timeout, an output flood or an exhausted budget is a processing failure, which
	 * is a different thing to tell the user - one says "this file will never work", the other
	 * says "try again".
	 */
	private static ReportFailure failureFor(OcrFailedException failure) {
		log.info("scanned page OCR failed: reason={}", failure.reason());
		return new ReportFailure(switch (failure.reason()) {
			case TIMEOUT, OUTPUT_CAP, BUDGET_EXHAUSTED, INTERRUPTED, START_FAILED ->
					ReportErrorCode.EXTRACTION_FAILED;
			case EXIT_CODE -> ReportErrorCode.UNREADABLE;
		});
	}

	/**
	 * Joins the pages, applies the length rules, and says how the text was obtained.
	 *
	 * @param ocrPages the indexes whose text came from OCR
	 * @param read the OCR pages, in document order, for the confidence index
	 */
	private ExtractedText finish(List<String> pageTexts, int pageCount, int lowTextPages,
			Set<Integer> ocrPages, List<OcrPage> read) {

		StringBuilder combined = new StringBuilder();
		int pagesWithText = 0;
		int ocrPagesWithText = 0;

		for (int index = 0; index < pageTexts.size(); index++) {
			String pageText = pageTexts.get(index);
			if (pageText.isEmpty()) {
				continue;
			}

			pagesWithText++;
			if (ocrPages.contains(index)) {
				ocrPagesWithText++;
			}

			if (!combined.isEmpty()) {
				// Blank line between pages: it tells the model where a page ended without
				// adding anything it could mistake for a value.
				combined.append("\n\n");
			}
			combined.append(pageText);
		}

		String text = TextNormalizer.normalize(combined.toString());

		if (text.length() < minTotalChars) {
			// A scanned PDF with OCR off lands here, as it did in phase 2, and so does one
			// whose pages OCR could make nothing of - a blank scan, or a photograph of a
			// ceiling. Both are the same answer to the user (docs/dataflow.md section 4.2).
			throw new ReportFailure(ReportErrorCode.UNREADABLE);
		}
		if (text.length() > maxTextChars) {
			// Re-checked after OCR: the text layer was within the limit, and OCR adds to it.
			throw new ReportFailure(ReportErrorCode.DOCUMENT_TOO_LONG);
		}

		TextSource textSource = textSourceFor(pagesWithText, ocrPagesWithText);
		log.info("pdf extracted: pages={} lowTextPages={} ocrPages={} textSource={} chars={}",
				pageCount, lowTextPages, ocrPagesWithText, textSource, text.length());

		return new ExtractedText(text, pageCount, lowTextPages, textSource,
				ConfidenceIndex.of(read));
	}

	/**
	 * {@code TEXT_LAYER}, {@code OCR} or {@code MIXED}, counted over the pages that contributed
	 * text.
	 *
	 * <p>Pages that contributed nothing are deliberately not counted. A blank trailing page is
	 * common in scans, and letting one decide that a wholly OCR'd report is {@code MIXED} would
	 * change the notice the reader sees for no reason that matters to them.
	 */
	private static TextSource textSourceFor(int pagesWithText, int ocrPagesWithText) {
		if (ocrPagesWithText == 0) {
			return TextSource.TEXT_LAYER;
		}
		return ocrPagesWithText == pagesWithText ? TextSource.OCR : TextSource.MIXED;
	}
}
