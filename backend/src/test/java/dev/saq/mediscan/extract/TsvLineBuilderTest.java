package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TsvLineBuilder} against hand-made TSV ({@code docs/phase-4-lld.md} 6.3).
 *
 * <p>The fixtures use the real column order and the real {@code conf} format of Tesseract 5.x,
 * both checked against {@code tesseract 5.4.0} locally and {@code 5.3.4} in the runtime image:
 *
 * <pre>
 * level page_num block_num par_num line_num word_num left top width height conf text
 * </pre>
 *
 * <p>Two of those columns are easy to get wrong and are pinned by tests here. {@code conf} is a
 * <strong>float</strong>, so an integer parse throws on every real row. And {@code line_num}
 * restarts inside each paragraph, so lines have to be grouped by the
 * {@code (block, par, line)} triple - grouping by {@code line_num} alone merges unrelated rows
 * from different paragraphs into one line.
 */
class TsvLineBuilderTest {

	private static final String HEADER =
			"level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\t"
					+ "left\ttop\twidth\theight\tconf\ttext";

	/** One level-5 word row. */
	private static String word(int block, int par, int line, int wordNum,
			int left, int width, String conf, String text) {

		return "5\t1\t" + block + "\t" + par + "\t" + line + "\t" + wordNum
				+ "\t" + left + "\t100\t" + width + "\t20\t" + conf + "\t" + text;
	}

	private static String tsv(String... rows) {
		return HEADER + "\n" + String.join("\n", rows) + "\n";
	}

	private static OcrPage build(String... rows) {
		return TsvLineBuilder.build(tsv(rows));
	}

	/** The lines that carry text, dropping the blank ones that mark paragraph breaks. */
	private static List<String> nonBlankLines(OcrPage page) {
		return page.lines().stream()
				.map(OcrLine::text)
				.filter(text -> !text.isEmpty())
				.toList();
	}

	@Nested
	@DisplayName("line endings")
	class LineEndings {

		/**
		 * The regression that the OCR evaluation found (2026-11-01).
		 *
		 * <p>Tesseract writes CRLF on Windows. The text column is the last field on a row, so
		 * splitting the output on {@code "\n"} alone left a carriage return glued to every word
		 * - and because {@code TextNormalizer} turns a carriage return into a newline, a lab
		 * table reached the model as one word per line with the column gaps as leading spaces.
		 *
		 * <p>Nothing failed loudly. Values still matched as whole tokens, so the support check
		 * passed and the extraction looked fine; what broke was every line-shaped thing
		 * downstream - {@code ResultRowDetector} saw no result rows, so masking protected
		 * nothing and the confidence lookup had no rows to prefer. The fixtures in the rest of
		 * this class all used {@code "\n"}, which is why the unit tests were green while real
		 * OCR was mangled on every developer machine.
		 */
		@Test
		@DisplayName("CRLF output rebuilds the same lines as LF output")
		void handlesCarriageReturns() {
			String[] rows = {
					word(1, 1, 1, 1, 83, 158, "96.0", "Potassium"),
					word(1, 1, 1, 2, 500, 48, "94.0", "5.4"),
					word(1, 1, 1, 3, 700, 90, "95.0", "mmol/L") };

			OcrPage unix = TsvLineBuilder.build(HEADER + "\n" + String.join("\n", rows) + "\n");
			OcrPage windows = TsvLineBuilder.build(
					HEADER + "\r\n" + String.join("\r\n", rows) + "\r\n");

			assertThat(windows.text()).isEqualTo(unix.text());
			assertThat(windows.lines()).hasSize(1);
			assertThat(windows.text()).doesNotContain("\r").doesNotContain("\n");
		}

		@Test
		@DisplayName("a word carries no carriage return into its text or its offsets")
		void wordsAreClean() {
			OcrPage page = TsvLineBuilder.build(HEADER + "\r\n"
					+ word(1, 1, 1, 1, 83, 158, "96.0", "Potassium") + "\r\n"
					+ word(1, 1, 1, 2, 500, 48, "94.0", "5.4") + "\r\n");

			// The offsets are what the confidence lookup reads, so a stray character in a word
			// shifts every one after it and the wrong characters get blamed for a low score.
			OcrWord value = page.lines().getFirst().words().get(1);
			assertThat(value.text()).isEqualTo("5.4");
			assertThat(page.text().substring(value.start(), value.end())).isEqualTo("5.4");
		}
	}

	@Nested
	@DisplayName("rows kept and dropped")
	class RowFiltering {

		@Test
		@DisplayName("keeps level-5 words and ignores the page, block, paragraph and line rows")
		void keepsOnlyWordRows() {
			// Levels 1 to 4 are the hierarchy Tesseract emits above each word. They carry
			// conf = -1 and empty text, which is exactly what the filter looks for.
			OcrPage page = build(
					"1\t1\t0\t0\t0\t0\t0\t0\t2480\t1100\t-1\t",
					"2\t1\t1\t0\t0\t0\t82\t42\t518\t18\t-1\t",
					"3\t1\t1\t1\t0\t0\t82\t42\t518\t18\t-1\t",
					"4\t1\t1\t1\t1\t0\t82\t42\t518\t18\t-1\t",
					word(1, 1, 1, 1, 83, 158, "96.723816", "Potassium"));

			assertThat(page.lines()).hasSize(1);
			assertThat(page.text()).isEqualTo("Potassium");
			assertThat(page.wordCount()).isEqualTo(1);
		}

		@Test
		@DisplayName("drops a word with no text and a word Tesseract refused to score")
		void dropsEmptyAndUnscoredWords() {
			OcrPage page = build(
					word(1, 1, 1, 1, 83, 158, "95.0", "Sodium"),
					word(1, 1, 1, 2, 250, 10, "95.0", ""),
					word(1, 1, 1, 3, 280, 10, "   ", "ignored-blank-conf"),
					word(1, 1, 1, 4, 300, 48, "-1", "garbage"),
					word(1, 1, 1, 5, 400, 48, "97.0", "139"));

			// Asserted on the words rather than the spacing: these coordinates are 159 px
			// apart, which is a column gap, and the spacing rule has tests of its own.
			assertThat(page.lines().get(0).words())
					.extracting(OcrWord::text)
					.containsExactly("Sodium", "139");
		}

		@Test
		@DisplayName("a page with no usable rows is empty rather than a blank line")
		void emptyPage() {
			assertThat(build("1\t1\t0\t0\t0\t0\t0\t0\t10\t10\t-1\t").isEmpty()).isTrue();
			assertThat(TsvLineBuilder.build("").isEmpty()).isTrue();
			assertThat(TsvLineBuilder.build(HEADER + "\n").isEmpty()).isTrue();
		}

		@Test
		@DisplayName("a malformed row is skipped rather than failing the page")
		void skipsMalformedRows() {
			// A truncated line can happen if the output cap cut the stream mid-row. One
			// unreadable row must not cost the whole page.
			OcrPage page = build(
					word(1, 1, 1, 1, 83, 158, "95.0", "Sodium"),
					"5\t1\t1\t1\t1",
					"not a tsv row at all",
					word(1, 1, 1, 2, 400, 48, "97.0", "139"));

			assertThat(page.lines().get(0).words())
					.extracting(OcrWord::text)
					.containsExactly("Sodium", "139");
		}
	}

	@Nested
	@DisplayName("grouping into lines and paragraphs")
	class Grouping {

		@Test
		@DisplayName("line_num restarting in a new paragraph starts a new line, not a merge")
		void groupsByBlockParagraphLine() {
			// Real output from the probe: blk4 par1 ln4 and blk4 par2 ln1 are different
			// physical lines. Grouping by line_num alone would merge them into "HDL
			// Triglycerides", inventing a result row that was never printed.
			OcrPage page = build(
					word(4, 1, 4, 1, 82, 50, "96.0", "HDL"),
					word(4, 2, 1, 1, 83, 228, "96.0", "Triglycerides"));

			// A paragraph change, so they are also separated by a blank line.
			assertThat(page.text()).isEqualTo("HDL\n\nTriglycerides");
			assertThat(nonBlankLines(page)).containsExactly("HDL", "Triglycerides");
		}

		@Test
		@DisplayName("words out of order in the TSV are sorted left to right")
		void sortsWordsByLeft() {
			OcrPage page = build(
					word(1, 1, 1, 3, 500, 40, "95.0", "third"),
					word(1, 1, 1, 1, 100, 40, "95.0", "first"),
					word(1, 1, 1, 2, 300, 40, "95.0", "second"));

			assertThat(page.lines().get(0).words())
					.extracting(OcrWord::text)
					.containsExactly("first", "second", "third");
		}

		@Test
		@DisplayName("a new paragraph is separated by a blank line")
		void blankLineBetweenParagraphs() {
			OcrPage page = build(
					word(1, 1, 1, 1, 80, 90, "96.0", "LIPID"),
					word(2, 1, 1, 1, 80, 60, "96.0", "Total"));

			assertThat(page.text()).isEqualTo("LIPID\n\nTotal");
		}

		@Test
		@DisplayName("lines within one paragraph are separated by a single newline")
		void singleNewlineWithinParagraph() {
			OcrPage page = build(
					word(6, 1, 1, 1, 83, 158, "97.0", "Potassium"),
					word(6, 1, 2, 1, 82, 105, "93.0", "Sodium"));

			assertThat(page.text()).isEqualTo("Potassium\nSodium");
		}
	}

	@Nested
	@DisplayName("column gaps")
	class ColumnGaps {

		@Test
		@DisplayName("a table row keeps its columns apart with three spaces")
		void wideGapsBecomeThreeSpaces() {
			// Exactly the Potassium row from the probe. Every gap here is far wider than two
			// character widths, so every one is a column boundary. Keeping them apart is what
			// lets ResultRowDetector and the extraction prompt still see a table.
			OcrPage page = build(
					word(6, 1, 1, 1, 83, 158, "96.7", "Potassium"),
					word(6, 1, 1, 2, 533, 47, "94.5", "5.4"),
					word(6, 1, 1, 3, 710, 106, "92.6", "mmol/L"),
					word(6, 1, 1, 4, 893, 120, "89.6", "3.5-5.0"),
					word(6, 1, 1, 5, 1216, 15, "92.0", "H"));

			assertThat(page.text()).isEqualTo("Potassium   5.4   mmol/L   3.5-5.0   H");
		}

		@Test
		@DisplayName("ordinary word spacing stays a single space")
		void narrowGapsStaySingleSpace() {
			// The header line from the probe: the gaps inside "Patient Name: Jane Q. Roe" are
			// ordinary spaces, and only the jump to MRN is a column.
			OcrPage page = build(
					word(2, 1, 1, 1, 83, 120, "97.0", "Patient"),
					word(2, 1, 1, 2, 225, 81, "97.0", "Name:"),
					word(2, 1, 1, 3, 335, 67, "93.0", "Jane"),
					word(2, 1, 1, 4, 424, 27, "85.0", "Q."),
					word(2, 1, 1, 5, 478, 50, "97.0", "Roe"),
					word(2, 1, 1, 6, 674, 64, "91.0", "MRN:"),
					word(2, 1, 1, 7, 764, 140, "69.0", "A1234567"));

			assertThat(page.text()).isEqualTo("Patient Name: Jane Q. Roe   MRN: A1234567");
		}

		@Test
		@DisplayName("a single-word line has no gaps to measure")
		void singleWordLine() {
			OcrPage page = build(word(1, 1, 1, 1, 83, 158, "96.0", "Potassium"));
			assertThat(page.text()).isEqualTo("Potassium");
		}

		@Test
		@DisplayName("overlapping words do not produce a negative gap")
		void overlappingWords() {
			// Tesseract can report boxes that touch or overlap slightly on tight kerning.
			OcrPage page = build(
					word(1, 1, 1, 1, 100, 50, "95.0", "ab"),
					word(1, 1, 1, 2, 140, 50, "95.0", "cd"));

			assertThat(page.text()).isEqualTo("ab cd");
		}
	}

	@Nested
	@DisplayName("word offsets and confidence")
	class Offsets {

		@Test
		@DisplayName("each word's offsets point at itself in the line it was placed in")
		void offsetsAddressTheWord() {
			OcrPage page = build(
					word(6, 1, 1, 1, 83, 158, "96.7", "Potassium"),
					word(6, 1, 1, 2, 533, 47, "94.5", "5.4"),
					word(6, 1, 1, 3, 710, 106, "92.6", "mmol/L"));

			OcrLine line = page.lines().get(0);
			for (OcrWord word : line.words()) {
				assertThat(line.text().substring(word.start(), word.end()))
						.as("offsets of a word must address that word")
						.isEqualTo(word.text());
			}
		}

		@Test
		@DisplayName("offsets are line-local, so the second line restarts at zero")
		void offsetsAreLineLocal() {
			OcrPage page = build(
					word(1, 1, 1, 1, 83, 90, "96.0", "first"),
					word(1, 1, 2, 1, 83, 90, "96.0", "second"));

			assertThat(page.lines().get(1).words().get(0).start()).isZero();
		}

		@Test
		@DisplayName("the float conf column is parsed, not truncated or rejected")
		void parsesFloatConfidence() {
			OcrPage page = build(word(1, 1, 1, 1, 83, 158, "96.723816", "Potassium"));

			assertThat(page.lines().get(0).words().get(0).confidence())
					.isEqualTo(96.723816);
		}

		@Test
		@DisplayName("mean confidence is the mean over kept words only")
		void meanConfidenceOverKeptWords() {
			OcrPage page = build(
					word(1, 1, 1, 1, 100, 40, "90.0", "a"),
					word(1, 1, 1, 2, 200, 40, "80.0", "b"),
					word(1, 1, 1, 3, 300, 40, "-1", "dropped"));

			assertThat(page.meanConfidence()).isEqualTo(85.0);
		}

		@Test
		@DisplayName("the lowest confidence overlapping a range is what the range gets")
		void lowestConfidenceOverRange() {
			OcrPage page = build(
					word(1, 1, 1, 1, 100, 40, "95.0", "Glucose"),
					word(1, 1, 1, 2, 200, 40, "42.0", "99"));

			OcrLine line = page.lines().get(0);
			int start = line.text().indexOf("99");

			assertThat(line.lowestConfidenceOverlapping(start, start + 2))
					.hasValue(42.0);
			// A range covering both words takes the worse of the two, never the average.
			assertThat(line.lowestConfidenceOverlapping(0, line.text().length()))
					.hasValue(42.0);
			assertThat(line.lowestConfidenceOverlapping(line.text().length(),
					line.text().length() + 5)).isEmpty();
		}
	}

	@Nested
	@DisplayName("text shape")
	class TextShape {

		@Test
		@DisplayName("Unicode read off a lab report survives intact")
		void keepsUnicode() {
			// µIU/mL and ≤ both appear on real reports, and both are multi-byte. A builder that
			// measured width in chars rather than code points would also mis-space these.
			OcrPage page = build(
					word(1, 1, 1, 1, 100, 60, "95.0", "TSH"),
					word(1, 1, 1, 2, 400, 40, "95.0", "≤0.5"),
					word(1, 1, 1, 3, 600, 90, "95.0", "µIU/mL"));

			assertThat(page.text()).contains("≤0.5").contains("µIU/mL");
		}

		@Test
		@DisplayName("the built text needs no normalising, so word offsets stay valid")
		void normalizerIsANoOp() {
			// The confidence lookup relies on this. TextNormalizer runs over the combined
			// document text afterwards; if it rewrote an OCR line, every offset recorded here
			// would silently point at the wrong characters. It touches tabs, CR, control
			// characters, trailing spaces and runs of blank lines - none of which this builder
			// can emit. The assertion is what keeps that true if either side changes.
			OcrPage page = build(
					word(6, 1, 1, 1, 83, 158, "96.7", "Potassium"),
					word(6, 1, 1, 2, 533, 47, "94.5", "5.4"),
					word(6, 1, 1, 3, 710, 106, "92.6", "mmol/L"),
					word(7, 1, 1, 1, 82, 70, "91.0", "eGFR"),
					word(7, 1, 1, 2, 532, 49, "93.0", ">60"));

			String text = page.text();
			assertThat(TextNormalizer.normalize(text)).isEqualTo(text);
		}

		@Test
		@DisplayName("no line carries a tab, a carriage return or trailing whitespace")
		void linesAreClean() {
			OcrPage page = build(
					word(1, 1, 1, 1, 100, 40, "95.0", "a"),
					word(1, 1, 1, 2, 900, 40, "95.0", "b"));

			for (OcrLine line : page.lines()) {
				assertThat(line.text()).doesNotContain("\t").doesNotContain("\r");
				assertThat(line.text()).isEqualTo(line.text().stripTrailing());
			}
		}
	}

	@Nested
	@DisplayName("privacy")
	class Privacy {

		@Test
		@DisplayName("no OCR type prints the text it holds")
		void typesHideTheirText() {
			OcrPage page = build(
					word(1, 1, 1, 1, 100, 140, "95.0", "ZZCANARY-Name-7391"),
					word(1, 1, 1, 2, 300, 40, "95.0", "238"));

			// A record prints its fields by default, and these records hold a patient's name
			// and values. Every one of them is overridden (CLAUDE.md rule 3).
			assertThat(page.toString()).doesNotContain("ZZCANARY").doesNotContain("238");
			assertThat(page.lines().toString()).doesNotContain("ZZCANARY").doesNotContain("238");
			for (OcrWord word : page.lines().get(0).words()) {
				assertThat(word.toString()).doesNotContain("ZZCANARY").doesNotContain("238");
			}
		}
	}

	@Test
	@DisplayName("a realistic multi-line page comes out as a readable table")
	void realisticPage() {
		// Trimmed from the probe's actual TSV output, so this is the shape the parser really
		// meets rather than an idealised one.
		// Both rows are blk4 par1, lines 1 and 2, exactly as the probe emitted them: a table
		// header and its first data row are one paragraph, so there is no blank line between.
		OcrPage page = build(
				word(4, 1, 1, 1, 83, 66, "95.0", "Test"),
				word(4, 1, 1, 2, 532, 103, "95.0", "Result"),
				word(4, 1, 1, 3, 711, 68, "95.0", "Unit"),
				word(4, 1, 2, 1, 83, 84, "95.0", "Total"),
				word(4, 1, 2, 2, 190, 193, "96.0", "Cholesterol"),
				word(4, 1, 2, 3, 532, 48, "97.0", "238"),
				word(4, 1, 2, 4, 710, 88, "75.0", "mg/dL"),
				word(4, 1, 2, 5, 891, 68, "93.0", "<200"),
				word(4, 1, 2, 6, 1216, 15, "89.0", "H"));

		assertThat(page.text()).isEqualTo("""
				Test   Result   Unit
				Total Cholesterol   238   mg/dL   <200   H""");
		assertThat(page.wordCount()).isEqualTo(9);
		// (95 + 95 + 95 + 95 + 96 + 97 + 75 + 93 + 89) / 9
		assertThat(page.meanConfidence()).isCloseTo(92.2, org.assertj.core.data.Offset.offset(0.1));
	}

	@Test
	@DisplayName("the unit, not the value, is the low-confidence word on clean rendered text")
	void unitsReadWorseThanValues() {
		// Observed on the probe and worth pinning as a fact about OCR rather than a bug: the
		// slashes in mg/dL and mIU/L score far below the digits beside them. It is why the
		// low-confidence rule covers values, ranges and dates but deliberately not units - a
		// misread unit never changes a flag (docs/phase-4-lld.md section 7).
		OcrPage page = build(
				word(4, 1, 2, 1, 532, 48, "97.0", "238"),
				word(4, 1, 2, 2, 710, 88, "75.0", "mg/dL"));

		OcrLine line = page.lines().get(0);
		double value = line.lowestConfidenceOverlapping(0, 3).orElseThrow();
		double unit = line.lowestConfidenceOverlapping(line.text().indexOf("mg/dL"),
				line.text().length()).orElseThrow();

		assertThat(value).isGreaterThan(unit);
	}

	@Test
	@DisplayName("words are grouped in the order the blocks appear, not by block number")
	void keepsTsvOrder() {
		// Tesseract emits blocks in reading order; the numbers are labels, not a sort key.
		List<String> rows = List.of(
				word(7, 1, 1, 1, 82, 70, "91.0", "eGFR"),
				word(6, 1, 1, 1, 83, 158, "97.0", "Potassium"));

		OcrPage page = TsvLineBuilder.build(tsv(rows.toArray(String[]::new)));

		// Different blocks, so a blank line separates them; the order is what this pins.
		assertThat(nonBlankLines(page)).containsExactly("eGFR", "Potassium");
	}
}
