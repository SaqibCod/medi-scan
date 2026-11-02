package dev.saq.mediscan.extract;

import java.util.List;

/**
 * What OCR made of one page.
 *
 * @param lines the rebuilt lines, in reading order
 * @param meanConfidence the mean confidence over every word kept, or 0 when there were none.
 *     Used for two things: the per-page log line, and the floor below which a page counts as
 *     empty rather than being fed to the model as garbage
 */
public record OcrPage(List<OcrLine> lines, double meanConfidence) {

	/** A page OCR produced nothing usable from. */
	static final OcrPage EMPTY = new OcrPage(List.of(), 0);

	public OcrPage(List<OcrLine> lines, double meanConfidence) {
		this.lines = List.copyOf(lines);
		this.meanConfidence = meanConfidence;
	}

	/**
	 * The page's text: lines joined with newlines.
	 *
	 * <p>Built on demand rather than stored, so there is one copy of the text in the record
	 * rather than two. On a 2 GB box with a ten-page scan that is worth caring about.
	 */
	public String text() {
		return String.join("\n", lines.stream().map(OcrLine::text).toList());
	}

	public int wordCount() {
		return lines.stream().mapToInt(line -> line.words().size()).sum();
	}

	public boolean isEmpty() {
		return lines.isEmpty();
	}

	/** Hides the text. Counts and the confidence are the whole of what may be logged. */
	@Override
	public String toString() {
		return "OcrPage[lines=" + lines.size() + ", words=" + wordCount()
				+ ", meanConfidence=" + Math.round(meanConfidence) + "]";
	}
}
