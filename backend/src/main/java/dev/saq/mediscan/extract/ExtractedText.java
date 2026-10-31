package dev.saq.mediscan.extract;

import dev.saq.mediscan.report.TextSource;

/**
 * The text pulled out of one source, with what was learned about the document on the way.
 *
 * <p>This is raw text: unmasked, straight from the file, and the most sensitive string the
 * pipeline handles. It exists in memory only, inside one job, and is dropped as soon as
 * masking has run ({@code CLAUDE.md} rule 1).
 *
 * @param text the extracted text, pages joined by a blank line
 * @param pageCount pages in the document; 1 for pasted text and samples
 * @param lowTextPages pages with too little text to be anything but a scan. Phase 4 runs OCR
 *     over exactly these; phase 2 only counted them, so the figure is worth logging
 * @param textSource how this text was obtained. Travels out of extraction because the client
 *     has to know whether a number was transcribed by OCR (contract 4.2), and because the
 *     low-confidence rule only applies when it was
 */
public record ExtractedText(String text, int pageCount, int lowTextPages, TextSource textSource) {

	/** For sources that have no pages: pasted text and samples. Never OCR. */
	static ExtractedText ofPlainText(String text) {
		return new ExtractedText(text, 1, 0, TextSource.TEXT_LAYER);
	}

	public int charCount() {
		return text.length();
	}

	/**
	 * Hides {@link #text()}.
	 *
	 * <p>The one thing in this system that must never be printed. Page counts and character
	 * counts are what the logs want anyway ({@code LLD} 16).
	 */
	@Override
	public String toString() {
		return "ExtractedText[pageCount=" + pageCount + ", lowTextPages=" + lowTextPages
				+ ", textSource=" + textSource + ", chars=" + text.length() + "]";
	}
}
