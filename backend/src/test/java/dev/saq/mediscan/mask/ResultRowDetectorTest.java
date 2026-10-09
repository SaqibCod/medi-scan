package dev.saq.mediscan.mask;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which lines count as lab results, and are therefore protected from masking.
 *
 * <p>Both directions matter, for opposite reasons. A missed result row lets a masking rule
 * reach into a lab value, which shows the patient a wrong number. An over-matched header line
 * leaves personal data unmasked. The detector is tuned to over-protect, so the negative cases
 * below - the lines that must stay maskable - are the ones holding it honest.
 */
class ResultRowDetectorTest {

	@ParameterizedTest(name = "protected: {0}")
	@ValueSource(strings = {
			// Unit present.
			"Total Cholesterol           238         mg/dL       <200           H",
			"Hemoglobin                 14.6       g/dL          13.5-17.5",
			"WBC                        6.8        x10^3/uL      4.0-11.0",
			"TSH                        0.21        uIU/mL     0.45-4.50",
			"Neutrophils                58         %             40-70",
			"MCV                        89.1       fL            80.0-100.0",
			"MCH                        30.1       pg            27.0-33.0",
			"eGFR 92 mL/min/1.73m2 >60",
			"Urinalysis WBC 3 /HPF 0-5",
			// Range present, no unit.
			"Platelet Count             245                      150-400",
			"Free T4 1.74 0.82-1.77",
			"Some Index 5 70 to 100",
			"Calcium 9.4 8.5 - 10.2",
			// Comparator range.
			"Non-HDL Cholesterol 200 <130",
			"Vitamin D 42 >=30",
			"Troponin 0.01 ≤0.04",
			// Trailing flag only.
			"Potassium 5.9 H",
			"Chloride 96 LOW",
			"Magnesium 1.4 *",
	})
	@DisplayName("a line with a number and a unit, range or flag is a result row")
	void detectsResultRows(String line) {
		assertThat(ResultRowDetector.isResultRow(line)).isTrue();
	}

	@ParameterizedTest(name = "maskable: {0}")
	@ValueSource(strings = {
			// The header block. These must stay maskable or masking does nothing at all.
			"Patient Name: Jane Q. Roe",
			"DOB: 04/12/1985",
			"Date of Birth: 22/07/1978",
			"Phone: (555) 555-0142",
			"Ordered by: Dr. Alan Whitfield, MD",
			"Physician: Dr. Henry Okonkwo",
			// An ID line has a number and nothing else, which is exactly why the detector
			// requires a second signal. If this were protected, IdentifierRule could never
			// fire and every MRN would survive into the stored text.
			"Patient ID: A1234567",
			"MRN: RB889214",
			"Accession: NC-4471829",
			"Specimen ID: 20260928-0114",
			"Medical Record No: LV-7781",
			// A collection date is a number with no unit, range or flag.
			"Collected: 09/28/2026",
			"Reported: 2026-10-03",
			// Prose and structure.
			"NORTHSIDE COMMUNITY LABORATORY",
			"Method: Enzymatic colorimetric. LDL calculated (Friedewald).",
			"",
			"TEST                        RESULT      UNIT        REFERENCE      FLAG",
	})
	@DisplayName("a header, label or date line is not a result row")
	void leavesHeadersMaskable(String line) {
		assertThat(ResultRowDetector.isResultRow(line)).isFalse();
	}

	@Test
	@DisplayName("a unit has to be its own token, not a substring")
	void requiresTokenisedUnits() {
		// "fl" inside "Fluid", "pg" inside "Upgrade": a substring search would protect both
		// of these lines and leave the names on them unmasked.
		assertThat(ResultRowDetector.isResultRow("Fluid collected 3 times")).isFalse();
		assertThat(ResultRowDetector.isResultRow("Upgrade 2 completed")).isFalse();

		// But a unit attached to its number, or in brackets, still counts.
		assertThat(ResultRowDetector.isResultRow("Hemoglobin 14.6g/dL")).isTrue();
		assertThat(ResultRowDetector.isResultRow("Hemoglobin 14.6 (g/dL)")).isTrue();
	}

	@Test
	@DisplayName("a line with no number is never a result row")
	void requiresANumber() {
		assertThat(ResultRowDetector.isResultRow("Total Cholesterol mg/dL")).isFalse();
		assertThat(ResultRowDetector.isResultRow("Result High")).isFalse();
	}

	@Test
	@DisplayName("a trailing flag has to be at the end of the line")
	void anchorsTrailingFlag() {
		// A capital H mid-line is not a flag; it is usually an initial.
		assertThat(ResultRowDetector.isResultRow("Patient 1 H Roe lives here")).isFalse();
		assertThat(ResultRowDetector.isResultRow("Potassium 5.9 H")).isTrue();
	}

	@Test
	@DisplayName("spans cover whole lines and exclude the newline")
	void spansCoverWholeLines() {
		String text = """
				Patient Name: Jane Q. Roe
				Total Cholesterol 238 mg/dL <200 H
				HDL Cholesterol 38 mg/dL >40 L""";

		ProtectedSpans protectedRows = ResultRowDetector.detect(text);

		assertThat(protectedRows.count()).isEqualTo(2);
		for (Span span : protectedRows.spans()) {
			String covered = text.substring(span.start(), span.end());
			assertThat(covered).doesNotContain("\n").contains("Cholesterol");
		}
		// The whole row is protected, including the test name and the printed range: the flag
		// is computed from that range, so masking half of it would change the result.
		String firstRow = text.substring(
				protectedRows.spans().get(0).start(), protectedRows.spans().get(0).end());
		assertThat(firstRow).isEqualTo("Total Cholesterol 238 mg/dL <200 H");
	}

	@Test
	@DisplayName("finds every result row in a whole sample report")
	void detectsRowsInWholeReport() {
		String text = """
				NORTHSIDE COMMUNITY LABORATORY
				Patient Name: Jane Q. Roe                 Accession: NC-4471829
				DOB: 04/12/1985                           Collected: 09/28/2026

				TEST                        RESULT      UNIT        REFERENCE      FLAG
				Total Cholesterol           238         mg/dL       <200           H
				HDL Cholesterol             38          mg/dL       >40            L
				LDL Cholesterol             172         mg/dL       <100           H
				Triglycerides               140         mg/dL       <150
				""";

		ProtectedSpans protectedRows = ResultRowDetector.detect(text);

		// The four value rows, and nothing else. The column header has no number.
		assertThat(protectedRows.count()).isEqualTo(4);
	}

	@Test
	@DisplayName("handles text with no result rows at all")
	void handlesNoRows() {
		ProtectedSpans protectedRows = ResultRowDetector.detect("Patient Name: Jane Q. Roe");

		assertThat(protectedRows.count()).isZero();
		assertThat(protectedRows.overlapsAny(0, 25)).isFalse();
	}
}
