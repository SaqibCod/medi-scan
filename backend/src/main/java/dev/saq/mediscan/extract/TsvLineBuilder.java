package dev.saq.mediscan.extract;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds lines of text from Tesseract's TSV output.
 *
 * <p>TSV rather than plain text output, because one run then gives both the text and a
 * confidence for every word. Asking for text and then asking again for confidences would mean
 * OCR'ing every page twice, on a box that can only afford to do it once
 * ({@code docs/phase-4-lld.md} decision O3).
 *
 * <p><strong>The format</strong>, verified against {@code tesseract 5.4.0} locally and
 * {@code 5.3.4} in the runtime image - identical in both:
 *
 * <pre>
 * level page_num block_num par_num line_num word_num left top width height conf text
 * </pre>
 *
 * <p>Levels 1 to 4 are the page, block, paragraph and line that contain each word; they carry
 * {@code conf = -1} and empty text, and only level 5 is kept. Two details of the format matter
 * enough to name:
 *
 * <ul>
 * <li>{@code conf} is a <strong>float</strong> ({@code 96.723816}), so it is parsed as a
 * double. An integer parse throws on every real row.</li>
 * <li>{@code line_num} restarts within each paragraph, so a line is identified by the
 * {@code (block, par, line)} triple. Grouping on {@code line_num} alone silently merges
 * unrelated rows from different paragraphs.</li>
 * </ul>
 *
 * <p><strong>Column gaps.</strong> A lab report is a column layout, and the gap between a test
 * name and its value is the only thing separating them. Words are joined with one space, except
 * where the pixel gap exceeds twice the line's median character width - there they get three,
 * which keeps {@code ResultRowDetector} seeing a value, a unit and a range as separate tokens
 * and keeps the extraction prompt seeing a table rather than a sentence.
 *
 * <p><strong>Nothing here is logged.</strong> Every string this class touches is report
 * content, and the types it returns all hide their text.
 */
final class TsvLineBuilder {

	/** Word rows. Levels 1 to 4 are the containing page, block, paragraph and line. */
	private static final int WORD_LEVEL = 5;

	private static final int COLUMNS = 12;

	// Column indexes, in the order Tesseract emits them.
	private static final int LEVEL = 0;
	private static final int BLOCK = 2;
	private static final int PARAGRAPH = 3;
	private static final int LINE = 4;
	private static final int LEFT = 6;
	private static final int WIDTH = 8;
	private static final int CONFIDENCE = 10;
	private static final int TEXT = 11;

	/** Beyond this multiple of the median character width, a gap is a column boundary. */
	private static final double COLUMN_GAP_FACTOR = 2.0;

	/** What a column boundary becomes in the text. */
	private static final String COLUMN_SEPARATOR = "   ";

	private TsvLineBuilder() {
	}

	/**
	 * Parses {@code tsv} into a page.
	 *
	 * <p>Unparseable rows are skipped rather than failing the page: a truncated final row is
	 * exactly what the output cap produces when it cuts the stream, and one bad row is not worth
	 * losing a page of results over.
	 */
	static OcrPage build(String tsv) {
		List<Row> rows = parseRows(tsv);
		if (rows.isEmpty()) {
			return OcrPage.EMPTY;
		}

		List<OcrLine> lines = new ArrayList<>();
		double confidenceTotal = 0;
		int wordCount = 0;

		for (List<Row> group : groupIntoLines(rows)) {
			if (group.getFirst().startsParagraph() && !lines.isEmpty()) {
				// A paragraph break is a blank line, carried as an empty OcrLine so that
				// joining the lines with newlines produces "...\n\n..." on its own.
				lines.add(new OcrLine("", List.of()));
			}

			group.sort((left, right) -> Integer.compare(left.left(), right.left()));
			OcrLine line = joinWords(group);
			lines.add(line);

			for (OcrWord word : line.words()) {
				confidenceTotal += word.confidence();
				wordCount++;
			}
		}

		return new OcrPage(lines, wordCount == 0 ? 0 : confidenceTotal / wordCount);
	}

	private static List<Row> parseRows(String tsv) {
		List<Row> rows = new ArrayList<>();
		boolean first = true;

		for (String rawLine : tsv.split("\n", -1)) {
			if (first) {
				// The header. Skipped positionally rather than by matching its text, because
				// a run with no words at all still emits it.
				first = false;
				if (rawLine.startsWith("level\t")) {
					continue;
				}
			}
			Row row = Row.parse(rawLine);
			if (row != null) {
				rows.add(row);
			}
		}
		return rows;
	}

	/** Splits the rows into lines, keeping the order Tesseract emitted them in. */
	private static List<List<Row>> groupIntoLines(List<Row> rows) {
		List<List<Row>> groups = new ArrayList<>();
		Row previous = null;

		for (Row row : rows) {
			if (previous == null || !row.sameLineAs(previous)) {
				row = row.markingParagraphStart(previous != null && !row.sameParagraphAs(previous));
				groups.add(new ArrayList<>());
			}
			groups.getLast().add(row);
			previous = row;
		}
		return groups;
	}

	/**
	 * Joins one line's words, recording where each one landed.
	 *
	 * <p>The offsets recorded here are what the confidence lookup uses, so they are taken from
	 * the builder as it writes rather than searched for afterwards - a search would find the
	 * wrong occurrence of a value that appears twice on a row.
	 */
	private static OcrLine joinWords(List<Row> rows) {
		double medianCharacterWidth = medianCharacterWidth(rows);
		double columnThreshold = COLUMN_GAP_FACTOR * medianCharacterWidth;

		StringBuilder text = new StringBuilder();
		List<OcrWord> words = new ArrayList<>(rows.size());
		Row previous = null;

		for (Row row : rows) {
			if (previous != null) {
				int gap = row.left() - previous.right();
				// A column boundary only when the median is known to be meaningful; a line of
				// zero-width boxes must not make every space a column.
				boolean column = medianCharacterWidth > 0 && gap > columnThreshold;
				text.append(column ? COLUMN_SEPARATOR : " ");
			}

			int start = text.length();
			text.append(row.text());
			words.add(new OcrWord(row.text(), row.confidence(), start, text.length(),
					row.left(), row.width()));
			previous = row;
		}

		return new OcrLine(text.toString(), words);
	}

	/**
	 * The median width of one character on this line.
	 *
	 * <p>Median rather than mean: one wildly mis-boxed word - which OCR produces on a smudge -
	 * would drag a mean far enough to turn every ordinary space into a column break, or none.
	 */
	private static double medianCharacterWidth(List<Row> rows) {
		double[] widths = rows.stream()
				.mapToDouble(row -> new OcrWord(row.text(), row.confidence(), 0, 0,
						row.left(), row.width()).averageCharacterWidth())
				.filter(width -> width > 0)
				.sorted()
				.toArray();

		if (widths.length == 0) {
			return 0;
		}
		int middle = widths.length / 2;
		return widths.length % 2 == 1
				? widths[middle]
				: (widths[middle - 1] + widths[middle]) / 2;
	}

	/**
	 * One level-5 row.
	 *
	 * <p>{@code toString()} is overridden for the same reason every other type here does it: the
	 * {@code text} field is a word off a patient's report.
	 */
	private record Row(int block, int paragraph, int line, int left, int width,
			double confidence, String text, boolean startsParagraph) {

		/** Returns null for a header, a non-word level, a blank word, or anything malformed. */
		static Row parse(String rawLine) {
			if (rawLine.isBlank()) {
				return null;
			}

			String[] fields = rawLine.split("\t", -1);
			if (fields.length < COLUMNS) {
				return null;
			}

			try {
				if (Integer.parseInt(fields[LEVEL].trim()) != WORD_LEVEL) {
					return null;
				}

				String text = fields[TEXT];
				if (text.isBlank()) {
					return null;
				}

				// A float, not an int. Negative means Tesseract declined to score the word,
				// which it pairs with text not worth keeping.
				double confidence = Double.parseDouble(fields[CONFIDENCE].trim());
				if (confidence < 0) {
					return null;
				}

				return new Row(
						Integer.parseInt(fields[BLOCK].trim()),
						Integer.parseInt(fields[PARAGRAPH].trim()),
						Integer.parseInt(fields[LINE].trim()),
						Integer.parseInt(fields[LEFT].trim()),
						Integer.parseInt(fields[WIDTH].trim()),
						confidence,
						text,
						false);
			}
			catch (NumberFormatException ex) {
				// A truncated or corrupted row. Skipped, and deliberately not logged: the row
				// contains a word of the report.
				return null;
			}
		}

		int right() {
			return left + width;
		}

		boolean sameLineAs(Row other) {
			return block == other.block && paragraph == other.paragraph && line == other.line;
		}

		boolean sameParagraphAs(Row other) {
			return block == other.block && paragraph == other.paragraph;
		}

		Row markingParagraphStart(boolean starts) {
			return starts
					? new Row(block, paragraph, line, left, width, confidence, text, true)
					: this;
		}

		@Override
		public String toString() {
			return "Row[block=" + block + ", paragraph=" + paragraph + ", line=" + line
					+ ", confidence=" + Math.round(confidence) + ", chars=" + text.length() + "]";
		}
	}
}
