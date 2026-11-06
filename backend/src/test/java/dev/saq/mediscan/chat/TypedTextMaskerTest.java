package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Masking what the caller typed ({@code LLD} 6.5, 13).
 *
 * <p>Both directions matter, as they do for report text: personal data has to go, and the
 * question has to survive well enough to answer. A masker that turned "Is my HDL Cholesterol
 * normal?" into a question about a redacted person would be worse than none - the reader would
 * get a confused answer and never know why.
 */
class TypedTextMaskerTest {

	private final TypedTextMasker masker = new TypedTextMasker();

	@Nested
	@DisplayName("personal data is removed")
	class Removed {

		@Test
		@DisplayName("an email address is masked")
		void email() {
			assertThat(masker.mask("Send it to me at jane.doe@example.com please"))
					.doesNotContain("jane.doe@example.com")
					.contains("[EMAIL]");
		}

		@Test
		@DisplayName("a phone number is masked")
		void phone() {
			assertThat(masker.mask("Call me on 555-123-4567 about this"))
					.doesNotContain("555-123-4567");
		}

		@Test
		@DisplayName("an SSN is masked")
		void ssn() {
			assertThat(masker.mask("My SSN is 123-45-6789"))
					.doesNotContain("123-45-6789")
					.contains("[SSN]");
		}

		@Test
		@DisplayName("a labelled identifier is masked")
		void identifier() {
			assertThat(masker.mask("My MRN: A1234567 - what does this mean?"))
					.doesNotContain("A1234567");
		}

		@Test
		@DisplayName("a labelled birth date is masked")
		void birthDate() {
			assertThat(masker.mask("DOB: 1984-03-02, is my LDL normal for my age?"))
					.doesNotContain("1984-03-02");
		}
	}

	@Nested
	@DisplayName("the question still makes sense")
	class Preserved {

		@ParameterizedTest
		@ValueSource(strings = {
				"Why is my LDL flagged?",
				"What does HDL Cholesterol measure?",
				"Which results are out of range?",
				"Is my Total Cholesterol of 238 high?",
				"What about Free T4 and TSH?",
		})
		@DisplayName("an ordinary question passes through untouched")
		void ordinaryQuestionsSurvive(String question) {
			assertThat(masker.mask(question)).isEqualTo(question);
		}

		@Test
		@DisplayName("capitalised test names are not treated as people")
		void testNamesAreNotNames() {
			// The reason the OpenNLP person model is kept off this path: it was trained on news
			// text and reads capitalised test names as people.
			String question = "Is my Free T4 lower than my Total Cholesterol?";
			assertThat(masker.mask(question)).isEqualTo(question);
		}

		@Test
		@DisplayName("a lab value in the question is left alone")
		void valuesSurvive() {
			// The guard needs the reader to be able to ask about a number on their report.
			assertThat(masker.mask("My LDL is 162 mg/dL, is that bad?"))
					.contains("162");
		}

		@Test
		@DisplayName("a name in ordinary prose is left alone")
		void unlabelledNamesAreLeftAlone() {
			// The two name rules are deliberately not on this path: they need a label a typed
			// question rarely has, and running them would only risk a false positive. Recorded
			// here as the accepted gap it is, not as a success.
			assertThat(masker.mask("My doctor Sarah said to ask about this"))
					.contains("Sarah");
		}
	}

	@Test
	@DisplayName("null and blank are returned unchanged")
	void nullAndBlank() {
		assertThat(masker.mask(null)).isNull();
		assertThat(masker.mask("   ")).isEqualTo("   ");
	}

	@Test
	@DisplayName("several pieces of personal data in one question are all masked")
	void severalAtOnce() {
		String masked = masker.mask(
				"I am jane@example.com, SSN 123-45-6789, call 555-123-4567 about my LDL of 162");

		assertThat(masked)
				.doesNotContain("jane@example.com")
				.doesNotContain("123-45-6789")
				.doesNotContain("555-123-4567")
				.contains("162");
	}
}
