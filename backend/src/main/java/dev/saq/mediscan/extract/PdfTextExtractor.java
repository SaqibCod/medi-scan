package dev.saq.mediscan.extract;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;

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
 * <p><strong>Sorted by position.</strong> A PDF's content stream order is whatever the
 * producing software emitted, which for a lab report's table can interleave columns.
 * {@code setSortByPosition(true)} gives reading order instead, so a result row arrives as one
 * line and the extraction prompt sees a table.
 */
@Component
public class PdfTextExtractor implements TextExtractor {

	private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

	private final int minCharsPerPage;
	private final int minTotalChars;
	private final int maxPages;
	private final int maxTextChars;

	public PdfTextExtractor(MediScanProperties properties) {
		this.minCharsPerPage = properties.extract().minCharsPerPage();
		this.minTotalChars = properties.extract().minTotalChars();
		this.maxPages = properties.extract().maxPages();
		this.maxTextChars = properties.upload().maxTextChars();
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

			return extractPages(document, pageCount);
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

	private ExtractedText extractPages(PDDocument document, int pageCount) throws IOException {
		PDFTextStripper stripper = new PDFTextStripper();
		stripper.setSortByPosition(true);

		StringBuilder combined = new StringBuilder();
		int lowTextPages = 0;

		for (int page = 1; page <= pageCount; page++) {
			stripper.setStartPage(page);
			stripper.setEndPage(page);

			String pageText = stripper.getText(document).strip();
			if (pageText.length() < minCharsPerPage) {
				lowTextPages++;
			}
			if (!pageText.isEmpty()) {
				if (!combined.isEmpty()) {
					// Blank line between pages: it tells the model where a page ended without
					// adding anything it could mistake for a value.
					combined.append("\n\n");
				}
				combined.append(pageText);
			}

			// Checked inside the loop so a pathological PDF cannot build a 200 MB string
			// before the limit is noticed.
			if (combined.length() > maxTextChars) {
				throw new ReportFailure(ReportErrorCode.DOCUMENT_TOO_LONG);
			}
		}

		String text = TextNormalizer.normalize(combined.toString());

		if (text.length() < minTotalChars) {
			// A scanned PDF lands here: a valid document, real pages, no text layer. Until
			// phase 4 there is no OCR to fall back to, so UNREADABLE is the honest answer
			// (docs/dataflow.md section 4.2).
			throw new ReportFailure(ReportErrorCode.UNREADABLE);
		}
		if (text.length() > maxTextChars) {
			throw new ReportFailure(ReportErrorCode.DOCUMENT_TOO_LONG);
		}

		return new ExtractedText(text, pageCount, lowTextPages);
	}
}
