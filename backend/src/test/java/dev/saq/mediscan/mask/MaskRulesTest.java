package dev.saq.mediscan.mask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Each regex rule on its own, with the cases it must catch and the cases it must not.
 *
 * <p>Rules are tested through {@link #maskWith} - the rule's spans applied to the text - so
 * each case reads as "this input becomes that output" rather than as a list of offsets. The
 * negative cases are at least as important: every one of them is something on a real lab
 * report that an over-eager pattern would destroy.
 */
class MaskRulesTest {

	private static final Map<String, MaskRule> RULES = MaskRules.ordered().stream()
			.collect(Collectors.toMap(MaskRule::name, Function.identity()));

	@Nested
	@DisplayName("ssn")
	class Ssn {

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"SSN: 123-45-6789                    | SSN: [SSN]",
				"Social Security 987-65-4321 on file | Social Security [SSN] on file",
				"123-45-6789                         | [SSN]",
				"(123-45-6789)                       | ([SSN])",
				"SSN 001-02-0003 verified            | SSN [SSN] verified",
		})
		@DisplayName("masks a hyphenated social security number")
		void masksSsn(String input, String expected) {
			assertThat(maskWith("ssn", input)).isEqualTo(expected);
		}

		@ParameterizedTest
		@ValueSource(strings = {
				// A bare nine-digit run is an accession number far more often than an SSN,
				// and is the identifier rule's job.
				"Accession: 123456789",
				// Wrong group lengths.
				"Code: 12-345-6789",
				"Code: 1234-56-789",
				// Part of a longer run, so not a standalone number.
				"Specimen 9123-45-67890",
				// A reference range, which must never be touched.
				"Platelet Count 245 150-400",
		})
		@DisplayName("leaves everything else alone")
		void leavesOthersAlone(String input) {
			assertThat(maskWith("ssn", input)).isEqualTo(input);
		}
	}

	@Nested
	@DisplayName("email")
	class Email {

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"Contact: jane.roe@example.com        | Contact: [EMAIL]",
				"j.roe+lab@example.co.uk              | [EMAIL]",
				"Email a_b-c@sub.example.org today    | Email [EMAIL] today",
				"<marcus@example.com>                 | <[EMAIL]>",
				"Dr. Okonkwo (h.okonkwo@clinic.net)   | Dr. Okonkwo ([EMAIL])",
		})
		@DisplayName("masks an email address")
		void masksEmail(String input, String expected) {
			assertThat(maskWith("email", input)).isEqualTo(expected);
		}

		@ParameterizedTest
		@ValueSource(strings = {
				"No domain: jane.roe@",
				"No local part: @example.com",
				"Not an email: jane.roe at example.com",
				"Total Cholesterol 238 mg/dL <200",
				"x10^3/uL 4.0-11.0",
		})
		@DisplayName("leaves everything else alone")
		void leavesOthersAlone(String input) {
			assertThat(maskWith("email", input)).isEqualTo(input);
		}
	}

	@Nested
	@DisplayName("identifier")
	class Identifier {

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"MRN: A1234567                   | MRN: [ID]",
				"Patient ID: A1234567            | Patient ID: [ID]",
				"Medical Record No: LV-7781      | Medical Record No: [ID]",
				"Accession: NC-4471829           | Accession: [ID]",
				"Specimen ID: 20260928-0114      | Specimen ID: [ID]",
				"Account # 55512                 | Account # [ID]",
		})
		@DisplayName("masks the token after a label, keeping the label")
		void masksIdentifier(String input, String expected) {
			// The label survives on purpose: it keeps the line's structure legible to the
			// extraction model, which otherwise sees an orphaned colon.
			assertThat(maskWith("identifier", input)).isEqualTo(expected);
		}

		@ParameterizedTest
		@ValueSource(strings = {
				// No digit in the token, so it is a label followed by a word.
				"Patient ID: Not Provided",
				// No label, so nothing identifies this as an id.
				"A1234567",
				// A result row. The label words do not appear, and the value must survive.
				"Total Cholesterol 238 mg/dL <200 H",
				// A collection date is not an identifier.
				"Collected: 09/28/2026",
				// A name label, which is the name rule's job.
				"Patient Name: Jane Q. Roe",
		})
		@DisplayName("leaves everything else alone")
		void leavesOthersAlone(String input) {
			assertThat(maskWith("identifier", input)).isEqualTo(input);
		}
	}

	@Nested
	@DisplayName("birthDate")
	class BirthDate {

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"DOB: 04/12/1985                 | DOB: [DOB]",
				"D.O.B.: 1991-03-19              | D.O.B.: [DOB]",
				"Date of Birth: 22/07/1978       | Date of Birth: [DOB]",
				"Birth Date: 4.12.1985           | Birth Date: [DOB]",
				"DOB 12 Mar 1985                 | DOB [DOB]",
				"Date of Birth: March 12, 1985   | Date of Birth: [DOB]",
		})
		@DisplayName("masks a date next to a birth-date label, keeping the label")
		void masksBirthDate(String input, String expected) {
			assertThat(maskWith("birthDate", input)).isEqualTo(expected);
		}

		@ParameterizedTest
		@ValueSource(strings = {
				// The single most costly false positive in the whole masker: losing the
				// collection date removes the report from every trend chart.
				"Collected: 09/28/2026",
				"Collected: 02 Oct 2026",
				"Reported: 2026-10-03",
				"Specimen received 15/09/2026",
				// A label with no date near it.
				"Date of Birth: not recorded",
				// A result row that happens to contain a slash.
				"eGFR 92 mL/min/1.73m2 >60",
		})
		@DisplayName("never masks a date without a birth-date label")
		void leavesOtherDatesAlone(String input) {
			assertThat(maskWith("birthDate", input)).isEqualTo(input);
		}

		@Test
		@DisplayName("does not reach across a page to swallow the collection date")
		void doesNotReachAcrossColumns() {
			// A bounded gap matters here: an unbounded one would let a DOB label in the left
			// column claim the collection date in the right.
			String line = "DOB:" + " ".repeat(60) + "Collected: 09/28/2026";

			assertThat(maskWith("birthDate", line)).isEqualTo(line);
		}
	}

	@Nested
	@DisplayName("phone")
	class Phone {

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"Phone: (555) 555-0142      | Phone: [PHONE]",
				"Tel 555-555-0100           | Tel [PHONE]",
				"Call 555.555.0188          | Call [PHONE]",
				"+1 555 555 0119            | [PHONE]",
				"Fax: (555) 555-0133        | Fax: [PHONE]",
		})
		@DisplayName("masks a separated phone number")
		void masksPhone(String input, String expected) {
			assertThat(maskWith("phone", input)).isEqualTo(expected);
		}

		@ParameterizedTest
		@ValueSource(strings = {
				// Unseparated ten digits is an accession number as often as a phone number,
				// and a pattern loose enough to catch it also eats result rows.
				"Accession 5555550142",
				// Reference ranges and values, which a looser pattern would match.
				"Platelet Count 245 150-400",
				"Hemoglobin 14.6 g/dL 13.5-17.5",
				"Specimen ID: 20260928-0114",
				"Reported: 2026-10-03",
		})
		@DisplayName("leaves everything else alone")
		void leavesOthersAlone(String input) {
			assertThat(maskWith("phone", input)).isEqualTo(input);
		}
	}

	@Nested
	@DisplayName("labelledName")
	class LabelledName {

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"Patient Name: Jane Q. Roe        | Patient Name: [NAME]",
				"Patient Name: Marcus T. Elder    | Patient Name: [NAME]",
				"Name: Dolores Fenwick            | Name: [NAME]",
				"Referred by: Priya Ramachandran  | Referred by: [NAME]",
				"Physician: Henry Okonkwo         | Physician: [NAME]",
				"Signed by: A. Whitfield          | Signed by: [NAME]",
		})
		@DisplayName("masks a capitalised name after a name label")
		void masksLabelledName(String input, String expected) {
			assertThat(maskWith("labelledName", input)).isEqualTo(expected);
		}

		@ParameterizedTest
		@ValueSource(strings = {
				// A label with no capitalised word after it.
				"Patient Name: not recorded",
				// Labels that are not name labels.
				"Patient ID: A1234567",
				"Specimen ID: 20260928-0114",
				// Result rows, including ones whose test names are capitalised words.
				"Total Cholesterol 238 mg/dL <200 H",
				"Free T4 1.74 ng/dL 0.82-1.77",
				"Thyroid Peroxidase Antibodies Negative",
		})
		@DisplayName("leaves everything else alone")
		void leavesOthersAlone(String input) {
			assertThat(maskWith("labelledName", input)).isEqualTo(input);
		}
	}

	@Nested
	@DisplayName("honorificName")
	class HonorificName {

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"Ordered by: Dr. Alan Whitfield  | Ordered by: Dr. [NAME]",
				"Dr Henry Okonkwo                | Dr [NAME]",
				"Seen by Prof. Priya Ramachandran| Seen by Prof. [NAME]",
				"Mrs. Dolores Fenwick            | Mrs. [NAME]",
				"Reviewed by Doctor Marcus Elder | Reviewed by Doctor [NAME]",
		})
		@DisplayName("masks the name after an honorific, keeping the honorific")
		void masksHonorificName(String input, String expected) {
			// Keeping "Dr." makes it obvious to a reader that a clinician's name was removed
			// rather than the patient's.
			assertThat(maskWith("honorificName", input)).isEqualTo(expected);
		}

		@ParameterizedTest
		@ValueSource(strings = {
				"Dr. 12345",
				"Drug screen negative",
				"Total Cholesterol 238 mg/dL <200 H",
				"Collected: 09/28/2026",
				"Method: Enzymatic colorimetric",
		})
		@DisplayName("leaves everything else alone")
		void leavesOthersAlone(String input) {
			assertThat(maskWith("honorificName", input)).isEqualTo(input);
		}
	}

	@Test
	@DisplayName("a candidate inside a result row is one the masker has to overrule")
	void candidatesInsideResultRowsAreArbitrated() {
		// A line engineered to tempt several rules at once: a hyphenated number that reads as
		// an SSN, and capitalised words that read as a name.
		String row = "Patient Total Cholesterol 123-45-6789 mg/dL 150-400 H";
		ProtectedSpans protectedRows = ResultRowDetector.detect(row);

		assertThat(protectedRows.count()).isEqualTo(1);

		// Rules report what they see; they do not self-censor. That is deliberate - a rule
		// that silently dropped its own overlapping matches would leave the masker nothing to
		// count, and a rule reaching into lab values would never be noticed.
		List<Span> candidates = MaskRules.ordered().stream()
				.flatMap(rule -> rule.find(row, protectedRows).stream())
				.toList();
		assertThat(candidates).isNotEmpty();

		// Every one of them overlaps the protected row, so the masker rejects all of them and
		// the row survives untouched. MaskerTest.countsConflicts asserts the count.
		assertThat(candidates).allSatisfy(candidate ->
				assertThat(protectedRows.overlapsAny(candidate)).isTrue());
	}

	/** Applies one rule's spans to {@code text}, so a case reads as input and output. */
	private static String maskWith(String ruleName, String text) {
		MaskRule rule = RULES.get(ruleName);
		assertThat(rule).as("rule %s exists", ruleName).isNotNull();

		List<Span> spans = rule.find(text, ProtectedSpans.none());

		StringBuilder builder = new StringBuilder(text);
		List<Span> ordered = spans.stream().sorted().toList();
		for (int i = ordered.size() - 1; i >= 0; i--) {
			Span span = ordered.get(i);
			builder.replace(span.start(), span.end(), span.type().placeholder());
		}
		return builder.toString();
	}
}
