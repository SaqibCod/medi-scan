package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import dev.saq.mediscan.mask.MaskResult;
import dev.saq.mediscan.mask.Masker;
import dev.saq.mediscan.mask.ResultRowDetector;

/**
 * {@link ConfidenceIndex}: given a printed value, how sure was OCR about the characters it was
 * read from?
 *
 * <p>The test that matters most is {@link #survivesMasking()}. The whole design rests on one
 * claim - that a result row is byte-identical before and after masking, so a lookup inside a
 * line needs no translation between the raw and masked texts - and that test runs the real
 * {@code Masker} over real OCR output to check it, rather than taking it on trust.
 */
class ConfidenceIndexTest {

	/** The real predicate the validator will pass in. */
	private static boolean isResultRow(String line) {
		return ResultRowDetector.isResultRow(line);
	}

	private static ConfidenceIndex indexOf(OcrPage... pages) {
		return ConfidenceIndex.of(List.of(pages));
	}

	@Test
	@DisplayName("a report with no OCR doubts nothing")
	void noneIsEmpty() {
		assertThat(ConfidenceIndex.NONE.isEmpty()).isTrue();
		assertThat(ConfidenceIndex.NONE.lowestConfidenceFor("5.4", ConfidenceIndexTest::isResultRow))
				.isEmpty();
	}

	@Test
	@DisplayName("a value's confidence is the confidence of the word it was read from")
	void findsTheWordsConfidence() {
		ConfidenceIndex index = indexOf(FakeOcrEngine.pageWithLowConfidenceWord(
				"Potassium 5.4 mmol/L 3.5-5.0", "5.4", 62, 96));

		assertThat(index.lowestConfidenceFor("5.4", ConfidenceIndexTest::isResultRow))
				.hasValue(62.0);
		assertThat(index.lowestConfidenceFor("3.5-5.0", ConfidenceIndexTest::isResultRow))
				.hasValue(96.0);
	}

	@Test
	@DisplayName("the lowest of several occurrences wins")
	void takesTheMinimumAcrossOccurrences() {
		// The same value on two rows, read well once and badly once. A flag must not be put on
		// it because one of the two readings happened to be confident.
		OcrPage first = FakeOcrEngine.pageWithLowConfidenceWord(
				"Glucose 99 mg/dL 70-100", "99", 95, 95);
		OcrPage second = FakeOcrEngine.pageWithLowConfidenceWord(
				"Glucose 99 mg/dL 70-100", "99", 41, 95);

		assertThat(indexOf(first, second)
				.lowestConfidenceFor("99", ConfidenceIndexTest::isResultRow))
				.hasValue(41.0);
	}

	@Test
	@DisplayName("a multi-word value takes the worst of its words")
	void multiWordValueTakesTheWorstWord() {
		// OCR splits a range across the dash often enough that this is the common case, not an
		// edge one. "3.5 - 5.0" is three words, and any of them being doubtful taints it.
		OcrPage page = FakeOcrEngine.pageWithLowConfidenceWord(
				"Potassium 5.4 mmol/L 3.5 - 5.0", "5.0", 55, 97);

		assertThat(indexOf(page).lowestConfidenceFor("3.5 - 5.0",
				ConfidenceIndexTest::isResultRow)).hasValue(55.0);
	}

	@Test
	@DisplayName("whole-token matching, so a value is not found inside a longer number")
	void doesNotMatchInsideALongerNumber() {
		// 15.42 must not answer for 5.4. Same rule as the support check, because it is the
		// same code.
		OcrPage page = FakeOcrEngine.page("Cholesterol 15.42 mg/dL 0-20", 88);

		assertThat(indexOf(page).lowestConfidenceFor("5.4", ConfidenceIndexTest::isResultRow))
				.isEmpty();
	}

	@Test
	@DisplayName("a value found nowhere in the OCR text returns empty")
	void unknownValueIsEmpty() {
		OcrPage page = FakeOcrEngine.page("Potassium 5.4 mmol/L 3.5-5.0", 96);

		// The caller treats this as "not to be trusted" and counts it, so a lookup bug shows
		// up as a number rather than passing values through unchecked.
		assertThat(indexOf(page).lowestConfidenceFor("7.1", ConfidenceIndexTest::isResultRow))
				.isEmpty();
	}

	@Test
	@DisplayName("result rows are preferred, but a value elsewhere is still found")
	void fallsBackToNonResultLines() {
		// "Reviewed value 4.2" is prose: no unit, no range, so not a result row. The approved
		// fallback (D7) still reads its confidence rather than giving up, because a measured
		// confidence anywhere beats no information at all.
		OcrPage page = FakeOcrEngine.pageWithLowConfidenceWord(
				"CHEMISTRY REPORT\nReviewed value 4.2 by the lab\nSodium 139 mmol/L 135-145",
				"4.2", 48, 95);

		assertThat(isResultRow("Reviewed value 4.2 by the lab")).isFalse();
		assertThat(indexOf(page).lowestConfidenceFor("4.2", ConfidenceIndexTest::isResultRow))
				.hasValue(48.0);
	}

	@Test
	@DisplayName("a value on a result row wins over the same value in prose")
	void resultRowTakesPrecedence() {
		// The result row read badly and the prose read well. The rule is defined over result
		// rows, so the row's confidence is the answer - the cautious one here.
		OcrPage page = new OcrPage(List.of(
				line("Note: the value 5.4 was confirmed", "5.4", 99),
				line("Potassium 5.4 mmol/L 3.5-5.0", "5.4", 51)), 75);

		assertThat(indexOf(page).lowestConfidenceFor("5.4", ConfidenceIndexTest::isResultRow))
				.hasValue(51.0);
	}

	@Test
	@DisplayName("column gaps do not hide a value")
	void findsValuesAcrossColumnGaps() {
		// The real shape of OCR'd table output: three spaces between columns.
		OcrPage page = FakeOcrEngine.pageWithLowConfidenceWord(
				"Total Cholesterol   238   mg/dL   <200   H", "238", 58, 96);

		assertThat(indexOf(page).lowestConfidenceFor("238", ConfidenceIndexTest::isResultRow))
				.hasValue(58.0);
		assertThat(indexOf(page).lowestConfidenceFor("<200", ConfidenceIndexTest::isResultRow))
				.hasValue(96.0);
	}

	@Test
	@DisplayName("the confidence survives masking shifting the text around it")
	void survivesMasking() {
		// The claim the whole approach rests on. The OCR text carries personal data above the
		// results; masking replaces it with shorter placeholders, moving every offset below.
		// If the lookup depended on document offsets it would now read the wrong characters.
		String ocrText = """
				RIVERBEND CLINICAL LABORATORY
				Patient Name: Jane Q. Roe        MRN: A1234567
				DOB: 04/12/1985                  Phone: (555) 123-4567
				Collected: 09/28/2026
				Potassium   5.4   mmol/L   3.5-5.0   H
				Sodium   139   mmol/L   135-145""";

		OcrPage page = FakeOcrEngine.pageWithLowConfidenceWord(ocrText, "5.4", 57, 96);
		ConfidenceIndex index = indexOf(page);

		Masker masker = new Masker(noOpenNlp());
		MaskResult masked = masker.mask(ocrText);

		// Masking really did move things: the text is shorter and the names are gone.
		assertThat(masked.maskedText()).isNotEqualTo(ocrText);
		assertThat(masked.maskedText()).doesNotContain("Jane Q. Roe").contains("[NAME]");

		// The result row is untouched, which is why the lookup still works.
		assertThat(masked.maskedText()).contains("Potassium   5.4   mmol/L   3.5-5.0   H");

		// And the confidence read from the masked text's value is still the right one.
		assertThat(index.lowestConfidenceFor("5.4", ConfidenceIndexTest::isResultRow))
				.hasValue(57.0);
		assertThat(index.lowestConfidenceFor("139", ConfidenceIndexTest::isResultRow))
				.hasValue(96.0);
	}

	@Test
	@DisplayName("every value the support check finds, the index can also locate")
	void agreesWithTheSupportCheck() {
		// The two must not diverge: a value that passes the support check and cannot be located
		// here would silently skip the low-confidence rule.
		String text = "Potassium   5.4   mmol/L   3.5-5.0   H\nTSH   0.8   mIU/L   0.4-4.0";
		ConfidenceIndex index = indexOf(FakeOcrEngine.page(text, 91));

		for (String value : List.of("5.4", "3.5-5.0", "0.8", "0.4-4.0", "139.0".substring(0, 3))) {
			boolean supported = ValueTokenMatcher.appearsIn(value, text);
			boolean located = index.lowestConfidenceFor(value, ConfidenceIndexTest::isResultRow)
					.isPresent();

			assertThat(located)
					.as("support check and confidence lookup must agree about '%s'", value)
					.isEqualTo(supported);
		}
	}

	@Test
	@DisplayName("the index prints sizes, never the text it holds")
	void toStringHidesText() {
		ConfidenceIndex index = indexOf(FakeOcrEngine.page(
				"ZZCANARY-Name-7391 238 mg/dL", 90));

		assertThat(index.toString())
				.doesNotContain("ZZCANARY")
				.doesNotContain("238")
				.contains("lines=")
				.contains("words=");
	}

	/** One line where {@code word} reads at {@code confidence} and the rest read at 96. */
	private static OcrLine line(String text, String word, double confidence) {
		return FakeOcrEngine.pageWithLowConfidenceWord(text, word, confidence, 96)
				.lines().getFirst();
	}

	/** The masker without the optional OpenNLP rule, which is off by default. */
	private static ObjectProvider<dev.saq.mediscan.mask.OpenNlpNameRule> noOpenNlp() {
		return new ObjectProvider<>() {
			@Override
			public dev.saq.mediscan.mask.OpenNlpNameRule getObject() {
				throw new UnsupportedOperationException();
			}

			@Override
			public dev.saq.mediscan.mask.OpenNlpNameRule getObject(Object... args) {
				throw new UnsupportedOperationException();
			}

			@Override
			public dev.saq.mediscan.mask.OpenNlpNameRule getIfAvailable() {
				return null;
			}

			@Override
			public dev.saq.mediscan.mask.OpenNlpNameRule getIfUnique() {
				return null;
			}
		};
	}
}
