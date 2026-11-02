package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.extract.ConfidenceIndex;
import dev.saq.mediscan.extract.OcrConfidenceFixtures;
import dev.saq.mediscan.report.Flag;
import dev.saq.mediscan.support.TestProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OCR low-confidence rule ({@code docs/phase-4-lld.md} section 7).
 *
 * <p>These tests describe the one thing this phase could get catastrophically wrong. OCR misreads
 * digits in ways that stay plausible - a lost decimal point turns {@code 5.4} into {@code 54} -
 * and the pipeline downstream of here cannot tell a misread number from a real one. It would
 * compute {@code HIGH} from either, and the reader would believe it.
 *
 * <p>So every test below is a variation on the same assertion: where OCR was unsure, the printed
 * text survives and <em>everything derived from it does not</em>. The reader sees what was read,
 * marked unclear, and is asked to check it against the paper.
 *
 * <p>The threshold is {@code ocr.min-value-confidence}, 80 in the defaults these tests use.
 */
class BiomarkerValidatorOcrTest {

	/**
	 * A masked report. The result rows are spaced the way OCR'd table output is, because that is
	 * what the index will be built over - and because a result row is byte-identical in the OCR
	 * text and the masked text, which is what makes the lookup work at all.
	 */
	private static final String TEXT = """
			RIVERBEND CLINICAL LABORATORY
			Patient Name: [NAME]
			Collected: 09/28/2026
			Total Cholesterol   238   mg/dL   <200   H
			HDL Cholesterol   38   mg/dL   >40   L
			Triglycerides   140   mg/dL   150-199
			""";

	private final BiomarkerValidator validator = new BiomarkerValidator(
			new BiomarkerCatalog(JsonMapper.builder().build()),
			new CollectedDateParser(Clock.fixed(
					LocalDate.of(2026, 10, 7).atStartOfDay(ZoneOffset.UTC).toInstant(),
					ZoneOffset.UTC)),
			TestProperties.defaults());

	// --- values --------------------------------------------------------------

	@Test
	@DisplayName("a value OCR was unsure of keeps its text and loses its number and flag")
	void doubtfulValueLosesItsNumberAndFlag() {
		// 238 against a range of <200 is unambiguously HIGH - if 238 is what was printed. OCR
		// read it at 58, so it might have been 23.8 or 288, and a flag computed from it would be
		// a guess presented as a fact.
		ValidationOutcome outcome = validate(doubtAbout("238", 58),
				row("Total Cholesterol", "238", "mg/dL", "<200"));

		ValidatedBiomarker marker = outcome.biomarkers().get(0);
		assertThat(marker.flag()).isEqualTo(Flag.UNKNOWN);
		assertThat(marker.numericValue()).isNull();

		// Kept, so the reader can see what was read and check it.
		assertThat(marker.rawValue()).isEqualTo("238");
		assertThat(marker.unit()).isEqualTo("mg/dL");
		assertThat(marker.referenceRangeText()).isEqualTo("<200");

		// The range itself was read well, so its bounds stay. Only the value is in doubt.
		assertThat(marker.refHigh()).isEqualTo(200.0);

		assertThat(outcome.lowConfidenceValues()).isEqualTo(1);
		assertThat(outcome.unlocatedValues()).isZero();
	}

	@Test
	@DisplayName("a value OCR was sure of is flagged exactly as a text-layer one")
	void confidentValueBehavesNormally() {
		ValidationOutcome outcome = validate(readWell(96),
				row("Total Cholesterol", "238", "mg/dL", "<200"));

		ValidatedBiomarker marker = outcome.biomarkers().get(0);
		assertThat(marker.flag()).isEqualTo(Flag.HIGH);
		assertThat(marker.numericValue()).isEqualTo(238.0);
		assertThat(outcome.lowConfidenceValues()).isZero();
	}

	@Test
	@DisplayName("a confidence exactly at the threshold is good enough")
	void thresholdIsInclusive() {
		// The threshold is a floor, not a gate to clear: below 80 is doubted, 80 is not. Worth
		// pinning, because an off-by-one here silently changes the unclear rate the eval reports.
		ValidationOutcome outcome = validate(doubtAbout("238", 80),
				row("Total Cholesterol", "238", "mg/dL", "<200"));

		assertThat(outcome.biomarkers().get(0).flag()).isEqualTo(Flag.HIGH);
		assertThat(outcome.lowConfidenceValues()).isZero();
	}

	@Test
	@DisplayName("one bad reading of a value taints it, even where another reading was good")
	void takesTheWorstReadingOfAValue() {
		// The same number printed twice - a panel repeated across pages, or a value echoed in a
		// summary line. If either reading was doubtful, the characters are doubtful.
		String text = """
				Total Cholesterol   238   mg/dL   <200   H
				Repeat: Total Cholesterol   238   mg/dL   <200   H
				""";
		ConfidenceIndex confidence = OcrConfidenceFixtures.readWithDoubtAbout(text, "238", 41, 97);

		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("Total Cholesterol", "238", "mg/dL", "<200")), null),
				text, confidence);

		assertThat(outcome.biomarkers().get(0).flag()).isEqualTo(Flag.UNKNOWN);
		assertThat(outcome.biomarkers().get(0).numericValue()).isNull();
	}

	// --- reference ranges ----------------------------------------------------

	@Test
	@DisplayName("a range OCR was unsure of loses its bounds, and the flag with them")
	void doubtfulRangeLosesItsBounds() {
		// A misread range is as dangerous as a misread value, and less likely to be noticed:
		// 150-199 read as 750-199 would make a normal triglyceride look LOW. The value here was
		// read well, so its number stays - there is nothing wrong with it, only with the
		// comparison we can no longer make.
		ValidationOutcome outcome = validate(doubtAbout("150-199", 44),
				row("Triglycerides", "140", "mg/dL", "150-199"));

		ValidatedBiomarker marker = outcome.biomarkers().get(0);
		assertThat(marker.flag()).isEqualTo(Flag.UNKNOWN);
		assertThat(marker.refLow()).isNull();
		assertThat(marker.refHigh()).isNull();

		// The printed range and value both survive as text.
		assertThat(marker.referenceRangeText()).isEqualTo("150-199");
		assertThat(marker.rawValue()).isEqualTo("140");
		assertThat(marker.numericValue()).isEqualTo(140.0);

		assertThat(outcome.lowConfidenceValues()).isEqualTo(1);
	}

	@Test
	@DisplayName("a row with both a doubtful value and a doubtful range counts once")
	void countsARowOnce() {
		// The count is the eval's unclear rate: rows the reader must check, not doubts found.
		String text = "Triglycerides   140   mg/dL   150-199\n";
		ConfidenceIndex confidence = OcrConfidenceFixtures.readAt(text, 40);

		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("Triglycerides", "140", "mg/dL", "150-199")), null),
				text, confidence);

		assertThat(outcome.biomarkers().get(0).flag()).isEqualTo(Flag.UNKNOWN);
		assertThat(outcome.lowConfidenceValues()).isEqualTo(1);
	}

	// --- the collection date -------------------------------------------------

	@Test
	@DisplayName("a collection date OCR was unsure of is dropped")
	void doubtfulDateIsDropped() {
		// The date parser's own defence is against ambiguity, and a misread digit is not
		// ambiguous: 09/28/2016 reads cleanly whether or not that 1 was really a 2. A wrong date
		// puts the result at the wrong point in a trend chart, which is worse than no point.
		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("Total Cholesterol", "238", "mg/dL", "<200")),
						"09/28/2026"),
				TEXT, doubtAbout("09/28/2026", 52));

		assertThat(outcome.collectedOn()).isNull();
	}

	@Test
	@DisplayName("a collection date OCR was sure of is kept")
	void confidentDateSurvives() {
		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("Total Cholesterol", "238", "mg/dL", "<200")),
						"09/28/2026"),
				TEXT, readWell(95));

		assertThat(outcome.collectedOn()).isEqualTo(LocalDate.of(2026, 9, 28));
	}

	// --- the boundaries of the rule ------------------------------------------

	@Test
	@DisplayName("a text-layer report is not touched by the rule at all")
	void textLayerReportsAreUnaffected() {
		// ConfidenceIndex.NONE is what a PDF text layer and pasted text produce. Every lookup is
		// empty, and an empty lookup must mean "no OCR here", not "not to be trusted" - or every
		// pre-phase-4 report would lose its flags.
		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("Total Cholesterol", "238", "mg/dL", "<200")),
						"09/28/2026"),
				TEXT, ConfidenceIndex.NONE);

		ValidatedBiomarker marker = outcome.biomarkers().get(0);
		assertThat(marker.flag()).isEqualTo(Flag.HIGH);
		assertThat(marker.numericValue()).isEqualTo(238.0);
		assertThat(marker.refHigh()).isEqualTo(200.0);
		assertThat(outcome.collectedOn()).isEqualTo(LocalDate.of(2026, 9, 28));
		assertThat(outcome.lowConfidenceValues()).isZero();
		assertThat(outcome.unlocatedValues()).isZero();
	}

	@Test
	@DisplayName("the two-argument validate is the text-layer path")
	void twoArgumentValidateDoubtsNothing() {
		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("Total Cholesterol", "238", "mg/dL", "<200")), null),
				TEXT);

		assertThat(outcome.biomarkers().get(0).flag()).isEqualTo(Flag.HIGH);
		assertThat(outcome.lowConfidenceValues()).isZero();
	}

	@Test
	@DisplayName("a value that passed the support check but cannot be located is doubted, and counted")
	void unlocatableValueIsDoubtedAndCounted() {
		// This should never happen: the support check and the confidence lookup use the same
		// matcher. If it does, the two have diverged - and the safe reading of "I cannot find
		// the characters this number came from" is not "they were fine". The count is how such a
		// bug becomes visible instead of silently disabling the rule.
		//
		// Forced here by giving the index different text from the masked text the support check
		// runs against, which is the shape any such divergence would take.
		ConfidenceIndex confidence =
				OcrConfidenceFixtures.readAt("Sodium   139   mmol/L   135-145\n", 97);

		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("Total Cholesterol", "238", "mg/dL", "<200")), null),
				TEXT, confidence);

		ValidatedBiomarker marker = outcome.biomarkers().get(0);
		assertThat(marker.flag()).isEqualTo(Flag.UNKNOWN);
		assertThat(marker.numericValue()).isNull();
		assertThat(outcome.lowConfidenceValues()).isEqualTo(1);
		assertThat(outcome.unlocatedValues()).isPositive();
	}

	@Test
	@DisplayName("a qualitative value is doubted like any other")
	void qualitativeValuesAreDoubtedToo() {
		// "Negative" has no number to lose, so the only thing the rule can take is the flag -
		// and it should, because a misread word is a misread result.
		String text = "Thyroid Peroxidase Antibodies   Negative   mIU/L   Negative\n";
		ConfidenceIndex confidence =
				OcrConfidenceFixtures.readWithDoubtAbout(text, "Negative", 51, 96);

		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(
						row("Thyroid Peroxidase Antibodies", "Negative", "mIU/L", "Negative")),
						null),
				text, confidence);

		assertThat(outcome.biomarkers().get(0).flag()).isEqualTo(Flag.UNKNOWN);
		assertThat(outcome.lowConfidenceValues()).isEqualTo(1);
	}

	@Test
	@DisplayName("the outcome prints counts, never a value")
	void toStringHidesContent() {
		ValidationOutcome outcome = validate(doubtAbout("238", 58),
				row("Total Cholesterol", "238", "mg/dL", "<200"));

		assertThat(outcome.toString())
				.doesNotContain("238")
				.doesNotContain("Cholesterol")
				.contains("lowConfidenceValues=1");
	}

	// --- helpers -------------------------------------------------------------

	private ValidationOutcome validate(ConfidenceIndex confidence,
			ModelExtraction.ModelRow... rows) {

		return validator.validate(new ModelExtraction(List.of(rows), null), TEXT, confidence);
	}

	/** OCR read {@link #TEXT}, and was unsure only about {@code word}. */
	private static ConfidenceIndex doubtAbout(String word, double confidence) {
		return OcrConfidenceFixtures.readWithDoubtAbout(TEXT, word, confidence, 96);
	}

	/** OCR read {@link #TEXT} and was equally sure of all of it. */
	private static ConfidenceIndex readWell(double confidence) {
		return OcrConfidenceFixtures.readAt(TEXT, confidence);
	}

	private static ModelExtraction.ModelRow row(String name, String value, String unit,
			String range) {

		return new ModelExtraction.ModelRow(name, value, unit, range);
	}
}
