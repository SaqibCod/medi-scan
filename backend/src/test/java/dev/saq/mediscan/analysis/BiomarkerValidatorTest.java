package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.report.Flag;
import dev.saq.mediscan.support.TestProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validation, and in particular the support check ({@code LLD} 11.2).
 *
 * <p>The support check is what makes everything downstream trustworthy: a value that is not in
 * the source text is dropped, so the model cannot invent a plausible result or quietly correct
 * one it thinks is wrong. The near-miss case - {@code 5.4} when the text says {@code 15.42} -
 * is the one worth the most attention, because that is what a misread column actually produces.
 */
class BiomarkerValidatorTest {

	private static final String TEXT = """
			Patient Name: [NAME]
			Collected: 09/28/2026
			Total Cholesterol           238         mg/dL       <200           H
			HDL Cholesterol             38          mg/dL       >40            L
			Triglycerides               140         mg/dL       <150
			Thyroid Peroxidase Antibodies   Negative               Negative
			Platelet Count              1,200       x10^3/uL      150-400
			""";

	private final BiomarkerValidator validator = new BiomarkerValidator(
			new BiomarkerCatalog(JsonMapper.builder().build()),
			new CollectedDateParser(Clock.fixed(
					LocalDate.of(2026, 10, 7).atStartOfDay(ZoneOffset.UTC).toInstant(),
					ZoneOffset.UTC)),
			TestProperties.defaults());

	// --- the happy path ------------------------------------------------------

	@Test
	@DisplayName("keeps a supported row and derives everything numeric from its strings")
	void keepsSupportedRow() {
		ValidationOutcome outcome = validate(row("Total Cholesterol", "238", "mg/dL", "<200"));

		assertThat(outcome.biomarkers()).hasSize(1);
		ValidatedBiomarker marker = outcome.biomarkers().get(0);

		// Strings straight from the model, exactly as printed.
		assertThat(marker.testName()).isEqualTo("Total Cholesterol");
		assertThat(marker.rawValue()).isEqualTo("238");
		assertThat(marker.unit()).isEqualTo("mg/dL");
		assertThat(marker.referenceRangeText()).isEqualTo("<200");

		// Everything else derived in code. None of it was in the model's schema.
		assertThat(marker.numericValue()).isEqualTo(238d);
		assertThat(marker.refLow()).isNull();
		assertThat(marker.refHigh()).isEqualTo(200d);
		assertThat(marker.flag()).isEqualTo(Flag.HIGH);
		assertThat(marker.testNameNorm()).isEqualTo("total cholesterol");
		assertThat(marker.biomarkerSlug()).isEqualTo("total-cholesterol");
		assertThat(marker.position()).isZero();
	}

	@Test
	@DisplayName("keeps report order in position")
	void keepsReportOrder() {
		ValidationOutcome outcome = validate(
				row("Total Cholesterol", "238", "mg/dL", "<200"),
				row("HDL Cholesterol", "38", "mg/dL", ">40"),
				row("Triglycerides", "140", "mg/dL", "<150"));

		assertThat(outcome.biomarkers()).extracting(ValidatedBiomarker::position)
				.containsExactly(0, 1, 2);
		assertThat(outcome.biomarkers()).extracting(ValidatedBiomarker::testName)
				.containsExactly("Total Cholesterol", "HDL Cholesterol", "Triglycerides");
	}

	@Test
	@DisplayName("recomputes the flag rather than trusting the printed one")
	void recomputesFlags() {
		ValidationOutcome outcome = validate(
				row("Total Cholesterol", "238", "mg/dL", "<200"),
				row("HDL Cholesterol", "38", "mg/dL", ">40"),
				row("Triglycerides", "140", "mg/dL", "<150"));

		assertThat(outcome.biomarkers()).extracting(ValidatedBiomarker::flag)
				.containsExactly(Flag.HIGH, Flag.LOW, Flag.NORMAL);
	}

	@Test
	@DisplayName("a qualitative value is kept, with no number and an UNKNOWN flag")
	void keepsQualitativeValue() {
		ValidationOutcome outcome = validate(
				row("Thyroid Peroxidase Antibodies", "Negative", null, "Negative"));

		ValidatedBiomarker marker = outcome.biomarkers().get(0);
		assertThat(marker.rawValue()).isEqualTo("Negative");
		// Shows as text rather than on a range bar (CLAUDE.md, "Qualitative values").
		assertThat(marker.numericValue()).isNull();
		assertThat(marker.flag()).isEqualTo(Flag.UNKNOWN);
		assertThat(marker.biomarkerSlug()).isEqualTo("thyroid-peroxidase-antibodies");
	}

	@Test
	@DisplayName("a value with a thousands separator is matched despite PDF spacing")
	void matchesThousandsSeparator() {
		ValidationOutcome outcome = validate(
				row("Platelet Count", "1,200", "x10^3/uL", "150-400"));

		assertThat(outcome.biomarkers()).hasSize(1);
		assertThat(outcome.biomarkers().get(0).numericValue()).isEqualTo(1200d);
	}

	// --- the support check ---------------------------------------------------

	@Test
	@DisplayName("drops a value that is not in the source text")
	void dropsUnsupportedValue() {
		// The model invented a result. This is the check that stops it reaching a patient.
		ValidationOutcome outcome = validate(row("Glucose", "99", "mg/dL", "70-100"));

		assertThat(outcome.biomarkers()).isEmpty();
		assertThat(outcome.dropsByReason())
				.containsEntry(ValidationOutcome.DropReason.UNSUPPORTED, 1);
	}

	@Test
	@DisplayName("drops a value that only appears inside a longer number")
	void dropsValueInsideLongerNumber() {
		String text = "LDL Cholesterol 15.42 mg/dL <100";

		// "5.4" is a substring of "15.42". A substring search would accept it, and the
		// patient would be shown a value their report does not contain - which is exactly
		// what a misread column produces.
		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("LDL Cholesterol", "5.4", "mg/dL", "<100")), null),
				text);

		assertThat(outcome.biomarkers()).isEmpty();
		assertThat(outcome.dropsByReason())
				.containsEntry(ValidationOutcome.DropReason.UNSUPPORTED, 1);
	}

	@Test
	@DisplayName("accepts a value adjacent to punctuation")
	void acceptsValueNextToPunctuation() {
		String text = "Total Cholesterol (238) mg/dL <200";

		ValidationOutcome outcome = validator.validate(new ModelExtraction(List.of(
				row("Total Cholesterol", "238", "mg/dL", "<200")), null), text);

		assertThat(outcome.biomarkers()).hasSize(1);
	}

	@Test
	@DisplayName("drops a value with the unit glued to it, erring on the safe side")
	void dropsValueWithAttachedUnit() {
		String text = "HDL 38mg/dL >40";

		// LLD 11.2 sets the boundaries at \w, so a letter immediately after the digits means
		// no match. That costs a legitimate row when extraction produces "38mg/dL" with no
		// space - uncommon, because PDFBox sorted by position usually keeps the column gap.
		//
		// Deliberately not loosened to allow trailing letters. The boundary's whole job is to
		// refuse a partial numeric match, and a dropped row is visible to the reader while a
		// wrongly matched one is not.
		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("HDL", "38", "mg/dL", ">40")), null), text);

		assertThat(outcome.biomarkers()).isEmpty();
		assertThat(outcome.dropsByReason())
				.containsEntry(ValidationOutcome.DropReason.UNSUPPORTED, 1);
	}

	@Test
	@DisplayName("drops every row when the text is empty")
	void dropsEverythingWithoutText() {
		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("Total Cholesterol", "238", "mg/dL", "<200")), null),
				"");

		assertThat(outcome.biomarkers()).isEmpty();
	}

	// --- malformed and duplicate rows ----------------------------------------

	@Test
	@DisplayName("drops a row with a blank name or value")
	void dropsBlankFields() {
		ValidationOutcome outcome = validate(
				row(null, "238", "mg/dL", "<200"),
				row("Total Cholesterol", null, "mg/dL", "<200"),
				row("  ", "238", "mg/dL", "<200"),
				row("Total Cholesterol", "  ", "mg/dL", "<200"));

		assertThat(outcome.biomarkers()).isEmpty();
		assertThat(outcome.dropsByReason())
				.containsEntry(ValidationOutcome.DropReason.MALFORMED, 4);
	}

	@Test
	@DisplayName("drops a row whose name or value is absurdly long")
	void dropsOverlongFields() {
		// Past 200 characters the model has returned prose rather than a field.
		ValidationOutcome outcome = validate(
				row("x".repeat(201), "238", "mg/dL", "<200"),
				row("Total Cholesterol", "2".repeat(201), "mg/dL", "<200"));

		assertThat(outcome.biomarkers()).isEmpty();
		assertThat(outcome.dropsByReason())
				.containsEntry(ValidationOutcome.DropReason.MALFORMED, 2);
	}

	@Test
	@DisplayName("drops a duplicate row")
	void dropsDuplicates() {
		ValidationOutcome outcome = validate(
				row("Total Cholesterol", "238", "mg/dL", "<200"),
				row("Total Cholesterol", "238", "mg/dL", "<200"),
				row("total cholesterol", "238", "mg/dL", "<200"));

		// Happens when a report repeats a panel across pages.
		assertThat(outcome.biomarkers()).hasSize(1);
		assertThat(outcome.dropsByReason())
				.containsEntry(ValidationOutcome.DropReason.DUPLICATE, 2);
	}

	@Test
	@DisplayName("keeps the same test twice when the value differs")
	void keepsSameTestWithDifferentValues() {
		String text = "Glucose 99 mg/dL 70-100\nGlucose 105 mg/dL 70-100";

		// A genuine repeat measurement is not a duplicate.
		ValidationOutcome outcome = validator.validate(new ModelExtraction(List.of(
				row("Glucose", "99", "mg/dL", "70-100"),
				row("Glucose", "105", "mg/dL", "70-100")), null), text);

		assertThat(outcome.biomarkers()).hasSize(2);
	}

	@Test
	@DisplayName("drops rows past the row cap")
	void dropsRowsPastTheCap() {
		String text = "Glucose 99 mg/dL 70-100";
		List<ModelExtraction.ModelRow> rows = new java.util.ArrayList<>();
		for (int i = 0; i < 5; i++) {
			rows.add(row("Glucose " + i, "99", "mg/dL", "70-100"));
		}

		BiomarkerValidator capped = new BiomarkerValidator(
				new BiomarkerCatalog(JsonMapper.builder().build()),
				new CollectedDateParser(Clock.systemUTC()),
				TestProperties.withMaxRows(2));

		ValidationOutcome outcome = capped.validate(new ModelExtraction(rows, null), text);

		assertThat(outcome.biomarkers()).hasSize(2);
		assertThat(outcome.dropsByReason())
				.containsEntry(ValidationOutcome.DropReason.OVER_LIMIT, 3);
	}

	// --- slugs and dates -----------------------------------------------------

	@Test
	@DisplayName("a name with no catalogue entry keeps the row with a null slug")
	void unknownNameKeepsRow() {
		String text = "Ferritin 120 ng/mL 30-400";

		ValidationOutcome outcome = validator.validate(
				new ModelExtraction(List.of(row("Ferritin", "120", "ng/mL", "30-400")), null), text);

		// No link is fine; a link to the wrong explanation would not be.
		assertThat(outcome.biomarkers()).hasSize(1);
		assertThat(outcome.biomarkers().get(0).biomarkerSlug()).isNull();
	}

	@Test
	@DisplayName("keeps a collection date that appears in the text")
	void keepsCollectionDate() {
		ValidationOutcome outcome = validator.validate(new ModelExtraction(
				List.of(row("Total Cholesterol", "238", "mg/dL", "<200")), "09/28/2026"), TEXT);

		assertThat(outcome.collectedOn()).isEqualTo(LocalDate.of(2026, 9, 28));
	}

	@Test
	@DisplayName("drops a collection date the model invented")
	void dropsInventedCollectionDate() {
		ValidationOutcome outcome = validator.validate(new ModelExtraction(
				List.of(row("Total Cholesterol", "238", "mg/dL", "<200")), "01/01/2020"), TEXT);

		assertThat(outcome.collectedOn()).isNull();
	}

	@Test
	@DisplayName("a missing collection date is null, not an error")
	void missingCollectionDateIsNull() {
		ValidationOutcome outcome = validate(row("Total Cholesterol", "238", "mg/dL", "<200"));

		assertThat(outcome.collectedOn()).isNull();
		assertThat(outcome.biomarkers()).hasSize(1);
	}

	// --- privacy -------------------------------------------------------------

	@Test
	@DisplayName("the result types hide report content in toString")
	void typesHideContent() {
		ValidationOutcome outcome = validate(row("Total Cholesterol", "238", "mg/dL", "<200"));

		assertThat(outcome.toString()).contains("kept=1").doesNotContain("238");
		assertThat(outcome.biomarkers().get(0).toString())
				.contains("position=0").contains("flag=HIGH")
				.doesNotContain("238").doesNotContain("Total Cholesterol");
		assertThat(new ModelExtraction(List.of(row("Total Cholesterol", "238", null, null)), null)
				.toString()).doesNotContain("238");
	}

	@Test
	@DisplayName("an empty extraction validates to an empty outcome")
	void handlesEmptyExtraction() {
		ValidationOutcome outcome = validator.validate(new ModelExtraction(null, null), TEXT);

		assertThat(outcome.isEmpty()).isTrue();
		assertThat(outcome.totalDropped()).isZero();
	}

	private ValidationOutcome validate(ModelExtraction.ModelRow... rows) {
		return validator.validate(new ModelExtraction(List.of(rows), null), TEXT);
	}

	private static ModelExtraction.ModelRow row(String name, String value, String unit,
			String range) {

		return new ModelExtraction.ModelRow(name, value, unit, range);
	}
}
