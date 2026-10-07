package dev.saq.mediscan.mask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The masker end to end, on whole reports.
 *
 * <p>Two obligations, pulled in opposite directions, and every test here is one or the other:
 *
 * <ul>
 * <li><strong>Personal data goes.</strong> Best-effort - a missed name is a disclosed
 * limitation, not a bug.</li>
 * <li><strong>Lab values stay, byte for byte.</strong> Not best-effort. A changed value is a
 * wrong number in front of a patient, so the result rows are compared character by character,
 * not merely checked for the presence of a digit.</li>
 * </ul>
 *
 * <p>Built without the OpenNLP rule, which is how production runs by default.
 */
class MaskerTest {

	private final Masker masker = new Masker(absent());

	// --- personal data is removed -------------------------------------------

	@Test
	@DisplayName("masks every kind of personal data in a lipid panel")
	void masksLipidPanelHeader() {
		String text = """
				NORTHSIDE COMMUNITY LABORATORY
				Phone: (555) 555-0100

				Patient Name: Jane Q. Roe                 Accession: NC-4471829
				DOB: 04/12/1985                           Specimen ID: 20260928-0114
				Patient ID: A1234567                      Collected: 09/28/2026
				Phone: (555) 555-0142                     Reported: 09/29/2026
				Ordered by: Dr. Alan Whitfield, MD        Fasting: Yes

				Total Cholesterol           238         mg/dL       <200           H
				HDL Cholesterol             38          mg/dL       >40            L
				LDL Cholesterol             172         mg/dL       <100           H
				Triglycerides               140         mg/dL       <150
				""";

		MaskResult result = masker.mask(text);
		String masked = result.maskedText();

		assertThat(masked)
				.doesNotContain("Jane Q. Roe")
				.doesNotContain("Alan Whitfield")
				.doesNotContain("A1234567")
				.doesNotContain("NC-4471829")
				.doesNotContain("20260928-0114")
				.doesNotContain("04/12/1985")
				.doesNotContain("555-0142")
				.doesNotContain("555-0100");

		assertThat(result.conflicts()).isZero();
	}

	@Test
	@DisplayName("masks a CBC header, including dd/MM dates and an MRN")
	void masksCbcHeader() {
		String text = """
				RIVERBEND DIAGNOSTIC SERVICES
				Patient Name: Marcus T. Elder             Accession: RB-9920415
				Date of Birth: 22/07/1978                 Specimen ID: 20260915-0477
				MRN: RB889214                             Collected: 15/09/2026
				Phone: (555) 555-0188                     Reported: 16/09/2026
				Referred by: Dr. Priya Ramachandran

				Hemoglobin                 14.6       g/dL          13.5-17.5
				Platelet Count             245        x10^3/uL      150-400
				""";

		String masked = masker.mask(text).maskedText();

		assertThat(masked)
				.doesNotContain("Marcus T. Elder")
				.doesNotContain("Priya Ramachandran")
				.doesNotContain("RB889214")
				.doesNotContain("RB-9920415")
				.doesNotContain("22/07/1978")
				.doesNotContain("555-0188");
	}

	@Test
	@DisplayName("leaves the placeholders it inserted, so masking is visible")
	void insertsPlaceholders() {
		String text = """
				Patient Name: Dolores Fenwick
				D.O.B.: 1991-03-19
				Medical Record No: LV-7781
				Phone: (555) 555-0119
				TSH                             0.21        uIU/mL     0.45-4.50
				""";

		String masked = masker.mask(text).maskedText();

		// Placeholders rather than deletion: the line keeps its shape for the extraction
		// model, and a reader can tell what was removed.
		assertThat(masked)
				.contains("Patient Name: [NAME]")
				.contains("D.O.B.: [DOB]")
				.contains("Medical Record No: [ID]")
				.contains("Phone: [PHONE]");
	}

	@Test
	@DisplayName("counts what it masked, by type")
	void countsByType() {
		String text = """
				Patient Name: Jane Q. Roe
				DOB: 04/12/1985
				Patient ID: A1234567
				Phone: (555) 555-0142
				Contact: jane.roe@example.com
				SSN: 123-45-6789
				Total Cholesterol 238 mg/dL <200 H
				""";

		MaskResult result = masker.mask(text);

		assertThat(result.counts())
				.containsEntry(MaskType.NAME, 1)
				.containsEntry(MaskType.DOB, 1)
				.containsEntry(MaskType.ID, 1)
				.containsEntry(MaskType.PHONE, 1)
				.containsEntry(MaskType.EMAIL, 1)
				.containsEntry(MaskType.SSN, 1);
		assertThat(result.totalMasked()).isEqualTo(6);
	}

	// --- lab values survive --------------------------------------------------

	@Test
	@DisplayName("every result row survives byte for byte")
	void resultRowsAreUnchanged() {
		List<String> rows = List.of(
				"Total Cholesterol           238         mg/dL       <200           H",
				"HDL Cholesterol             38          mg/dL       >40            L",
				"LDL Cholesterol             172         mg/dL       <100           H",
				"Triglycerides               140         mg/dL       <150",
				"Non-HDL Cholesterol         200         mg/dL       <130           H");

		String text = "Patient Name: Jane Q. Roe\nDOB: 04/12/1985\n" + String.join("\n", rows);

		String masked = masker.mask(text).maskedText();

		// Not "contains a 238" - the whole row, spacing included. Everything downstream
		// re-parses these strings, and the support check matches rawValue against this text.
		for (String row : rows) {
			assertThat(masked).contains(row);
		}
	}

	@Test
	@DisplayName("a qualitative value and its range survive")
	void qualitativeValuesSurvive() {
		String row = "Thyroid Peroxidase Antibodies   Negative               Negative";
		String text = "Patient Name: Dolores Fenwick\nTSH 0.21 uIU/mL 0.45-4.50\n" + row;

		assertThat(masker.mask(text).maskedText()).contains(row);
	}

	@Test
	@DisplayName("the collection date survives while the birth date goes")
	void collectionDateSurvives() {
		String text = """
				DOB: 04/12/1985
				Collected: 09/28/2026
				Reported: 09/29/2026
				Total Cholesterol 238 mg/dL <200 H
				""";

		String masked = masker.mask(text).maskedText();

		// The whole reason BirthDateRule is label-anchored. Losing the collection date would
		// silently drop the report from every trend chart.
		assertThat(masked).contains("Collected: 09/28/2026");
		assertThat(masked).contains("Reported: 09/29/2026");
		assertThat(masked).contains("DOB: [DOB]").doesNotContain("04/12/1985");
	}

	@Test
	@DisplayName("a test name that looks like a person's name survives")
	void testNamesSurvive() {
		// These are the spans a name finder most wants, and masking one would drop the row.
		String text = """
				Patient Name: Jane Q. Roe
				Hemoglobin A1c              5.4         %           4.0-5.6
				Vitamin B12                 410         pg/mL       200-900
				Free T4                     1.74        ng/dL       0.82-1.77
				""";

		String masked = masker.mask(text).maskedText();

		assertThat(masked)
				.contains("Hemoglobin A1c")
				.contains("Vitamin B12")
				.contains("Free T4");
		assertThat(masked).doesNotContain("Jane Q. Roe");
	}

	// --- arbitration ---------------------------------------------------------

	@Test
	@DisplayName("a more precise rule wins an overlapping span")
	void precisionOrderWins() {
		// Both the labelled-name rule and the honorific rule can claim this. The labelled
		// rule runs first, and its span excludes the honorific, so the result is the same
		// either way - what must not happen is a double replacement.
		String text = "Ordered by: Dr. Alan Whitfield\nTotal Cholesterol 238 mg/dL <200 H";

		String masked = masker.mask(text).maskedText();

		assertThat(masked).contains("Ordered by: Dr. [NAME]");
		assertThat(masked).doesNotContain("[NAME][NAME]");
		assertThat(masked).doesNotContain("Whitfield");
	}

	@Test
	@DisplayName("counts a conflict when a rule reaches into a result row")
	void countsConflicts() {
		// An SSN-shaped number inside a genuine result row. The row is protected, so the SSN
		// rule is overruled and the attempt is counted rather than applied.
		String text = "Total Cholesterol 123-45-6789 mg/dL 150-400 H";

		MaskResult result = masker.mask(text);

		assertThat(result.conflicts()).isPositive();
		assertThat(result.maskedText()).isEqualTo(text);
	}

	@Test
	@DisplayName("masks multiple spans on one line without corrupting offsets")
	void handlesMultipleSpansPerLine() {
		String text = "Patient Name: Jane Q. Roe  Patient ID: A1234567  Phone: (555) 555-0142\n"
				+ "Total Cholesterol 238 mg/dL <200 H";

		String masked = masker.mask(text).maskedText();

		// Right-to-left replacement is what makes this work: each substitution changes the
		// length of everything after it.
		assertThat(masked).contains("[NAME]").contains("[ID]").contains("[PHONE]");
		assertThat(masked)
				.doesNotContain("Jane Q. Roe")
				.doesNotContain("A1234567")
				.doesNotContain("555-0142");
		assertThat(masked).contains("Total Cholesterol 238 mg/dL <200 H");
	}

	@Test
	@DisplayName("no rule sees another rule's placeholder")
	void rulesNeverSeePlaceholders() {
		// Every rule runs against the same original string, so a [NAME] cannot become the
		// input to the identifier rule and be masked again as [ID].
		String text = "Patient Name: Jane Q. Roe\nPatient ID: A1234567\nGlucose 99 mg/dL 70-100";

		String masked = masker.mask(text).maskedText();

		assertThat(masked).doesNotContain("[[").doesNotContain("]]");
		assertThat(masked.split("\\[NAME]", -1)).hasSize(2);
		assertThat(masked.split("\\[ID]", -1)).hasSize(2);
	}

	// --- edge cases ----------------------------------------------------------

	@Test
	@DisplayName("text with nothing to mask comes back unchanged")
	void unchangedWhenNothingToMask() {
		String text = """
				Total Cholesterol 238 mg/dL <200 H
				HDL Cholesterol 38 mg/dL >40 L
				""";

		MaskResult result = masker.mask(text);

		assertThat(result.maskedText()).isEqualTo(text);
		assertThat(result.totalMasked()).isZero();
		assertThat(result.conflicts()).isZero();
	}

	@Test
	@DisplayName("handles text with no result rows at all")
	void handlesNoResultRows() {
		String text = "Patient Name: Jane Q. Roe\nDOB: 04/12/1985";

		MaskResult result = masker.mask(text);

		assertThat(result.maskedText()).contains("[NAME]").contains("[DOB]");
	}

	@Test
	@DisplayName("handles empty text")
	void handlesEmptyText() {
		MaskResult result = masker.mask("");

		assertThat(result.maskedText()).isEmpty();
		assertThat(result.totalMasked()).isZero();
	}

	@Test
	@DisplayName("MaskResult.toString hides the masked text")
	void toStringHidesText() {
		MaskResult result = masker.mask("Patient Name: Jane Q. Roe\nGlucose 99 mg/dL 70-100");

		String printed = result.toString();

		// This record holds the text about to be sent to a model and stored, so its
		// toString is the likeliest accidental route into a log line.
		assertThat(printed).contains("counts=").contains("conflicts=").contains("chars=");
		assertThat(printed).doesNotContain("Jane Q. Roe").doesNotContain("Glucose");
	}

	@Test
	@DisplayName("Span.toString hides the text it points at")
	void spanToStringHidesText() {
		assertThat(new Span(0, 11, MaskType.NAME).toString())
				.isEqualTo("Span[0..11, NAME]");
	}

	/** No OpenNLP rule, which is the default configuration. */
	private static ObjectProvider<OpenNlpNameRule> absent() {
		return new ObjectProvider<>() {
			@Override
			public OpenNlpNameRule getObject() {
				throw new UnsupportedOperationException();
			}

			@Override
			public OpenNlpNameRule getObject(Object... args) {
				throw new UnsupportedOperationException();
			}

			@Override
			public OpenNlpNameRule getIfAvailable() {
				return null;
			}

			@Override
			public OpenNlpNameRule getIfUnique() {
				return null;
			}
		};
	}
}
