package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The number guard and the sentence gate ({@code LLD} 7, 13; {@code CLAUDE.md} rule 18).
 *
 * <p>The claim under test: <strong>an answer can contain no number that is not on the
 * reader's report</strong>. A model saying "normal is under 130" has invented a clinical
 * cut-off, and nothing on screen distinguishes that from a figure copied off the paperwork.
 *
 * <p>The report behind these tests is the lipid sample: Total Cholesterol 238 (range &lt;200),
 * LDL 162 (&lt;100), HDL 52 (&gt;40), Triglycerides 140 (&lt;150), VLDL 28 (5-40), collected
 * 2026-09-28, 5 results of which 2 are high.
 */
class AnswerGuardTest {

	private static final AllowedNumbers LIPID_REPORT = AllowedNumbers.of(
			List.of("238", "<200", "162", "<100", "52", ">40", "140", "<150", "28", "5-40"),
			"Total Cholesterol 238 mg/dL <200\nLDL Cholesterol 162 mg/dL <100\n",
			List.of(5, 2, 3, 0, 0),
			LocalDate.of(2026, 9, 28));

	@Nested
	@DisplayName("numbers from the report are allowed")
	class Allowed {

		@ParameterizedTest
		@ValueSource(strings = {
				"Your LDL cholesterol is 162 mg/dL.",
				"The range printed on your report is <100.",
				"Total cholesterol is 238, above the range of <200.",
				"Your HDL is 52, which is above >40.",
				"Two of your 5 results are outside their ranges.",
				"The sample was collected on 28 September 2026.",
				"VLDL of 28 sits inside the 5-40 range.",
		})
		@DisplayName("a sentence quoting only report numbers passes")
		void reportNumbersPass(String sentence) {
			assertThat(guard().accept(sentence + " ")).containsExactly(sentence);
		}

		@ParameterizedTest
		@ValueSource(strings = {
				"Both of them are high.",
				"1. Ask your doctor what this means.",
				"There are 3 normal results.",
		})
		@DisplayName("small counting numbers and list markers pass")
		void countingNumbersPass(String sentence) {
			// Without these the guard would stop almost every list, and "1." is not a clinical
			// claim.
			assertThat(guard().accept(sentence + "\n\n")).isNotEmpty();
		}

		@Test
		@DisplayName("a value written with different trailing zeros still matches")
		void equivalentSpellingsMatch() {
			// "162.0" and "162" are the same number; both sides are normalised, so they match.
			assertThat(guard().accept("Your LDL is 162.0 mg/dL. ")).hasSize(1);
		}
	}

	@Nested
	@DisplayName("invented numbers stop the answer")
	class Rejected {

		@ParameterizedTest
		@ValueSource(strings = {
				"A normal LDL is under 130 mg/dL.",
				"Most labs use 199 as the upper limit.",
				"That is about 35% above the range.",
				"Levels above 240 are considered high.",
				"The optimal value is 70.",
		})
		@DisplayName("a sentence with a number that is not on the report is refused")
		void inventedNumbersAreRefused(String sentence) {
			AnswerGuard guard = guard();

			assertThat(guard.accept(sentence + " ")).isEmpty();
			assertThat(guard.stopped()).isTrue();
		}

		@Test
		@DisplayName("earlier sentences are kept, the offending one and the rest are not")
		void stopsAtTheOffendingSentence() {
			AnswerGuard guard = guard();

			List<String> passed = guard.accept(
					"Your LDL is 162 mg/dL. A normal LDL is under 130. It matters a lot. ");

			assertThat(passed).containsExactly("Your LDL is 162 mg/dL.");
			assertThat(guard.stopped()).isTrue();
		}

		@Test
		@DisplayName("nothing is emitted after a stop, however much more arrives")
		void nothingFollowsAStop() {
			AnswerGuard guard = guard();
			guard.accept("Normal is under 130. ");

			assertThat(guard.accept("Your LDL is 162 mg/dL. ")).isEmpty();
			assertThat(guard.finish()).isEmpty();
		}

		@ParameterizedTest
		@CsvSource({
				// A comparator glued to a digit must not hide it from extraction.
				"'Your result is ≤0.5 today. ', true",
				"'Anything ≥7.5 is unusual. ', true",
				// 5.0 is deliberately *not* here: 5 is on this report, as the lower bound of
				// the VLDL range 5-40, so the guard is right to allow it. Asserting a stop
				// would have been a test demanding the guard reject a number off the report.
				"'Your VLDL range is ≥5.0 at the low end. ', false",
				"'Your LDL is 162 mg/dL. ', false",
		})
		@DisplayName("a number attached to a Unicode comparator is still checked")
		void unicodeComparatorsAreChecked(String text, boolean shouldStop) {
			AnswerGuard guard = guard();
			guard.accept(text);

			assertThat(guard.stopped()).isEqualTo(shouldStop);
		}

		@Test
		@DisplayName("a decimal comma is read as a number, not as two")
		void decimalCommaIsOneNumber() {
			// "5,4" means 5.4 in much of the world. Reading it as 5 and 4 - both counting
			// numbers - would let an invented value through in pieces.
			AnswerGuard guard = guard();
			guard.accept("Your result is 5,4 mmol/L. ");

			assertThat(guard.stopped()).isTrue();
		}

		@Test
		@DisplayName("a percentage is checked like any other number")
		void percentagesAreChecked() {
			AnswerGuard guard = guard();
			guard.accept("That is 42% higher. ");

			assertThat(guard.stopped()).isTrue();
		}
	}

	@Nested
	@DisplayName("splitting into sentences")
	class Sentences {

		@Test
		@DisplayName("a decimal point does not end a sentence")
		void decimalPointIsNotASentenceEnd() {
			// The failure this prevents: splitting at the point checks "5" and "4 mmol/L"
			// separately, both of which look like counting numbers, so an invented 5.4 would
			// pass in two halves.
			AnswerGuard guard = guard();
			guard.accept("Your result is 5.4 mmol/L today. ");

			assertThat(guard.stopped()).isTrue();
		}

		@Test
		@DisplayName("a sentence is only emitted once it is complete")
		void partialSentencesWait() {
			AnswerGuard guard = guard();

			assertThat(guard.accept("Your LDL is ")).isEmpty();
			assertThat(guard.accept("162 mg/dL. ")).containsExactly("Your LDL is 162 mg/dL.");
		}

		@Test
		@DisplayName("the last sentence is emitted when the stream ends")
		void finishFlushesTheLastSentence() {
			// No trailing space after the final full stop, which is how answers actually end.
			AnswerGuard guard = guard();
			guard.accept("Your LDL is 162 mg/dL.");

			assertThat(guard.finish()).containsExactly("Your LDL is 162 mg/dL.");
		}

		@Test
		@DisplayName("a blank line ends a sentence")
		void blankLineEndsASentence() {
			AnswerGuard guard = guard();

			assertThat(guard.accept("A heading\n\n")).isNotEmpty();
		}

		@Test
		@DisplayName("several sentences in one chunk all come back")
		void severalSentencesInOneChunk() {
			assertThat(guard().accept("One is 52. Two is 28. Three is 140. "))
					.containsExactly("One is 52.", " Two is 28.", " Three is 140.");
		}

		@Test
		@DisplayName("questions and exclamations end sentences too")
		void otherTerminators() {
			assertThat(guard().accept("Is your LDL 162? Yes! ")).hasSize(2);
		}

		@Test
		@DisplayName("a list marker does not end a sentence")
		void listMarkerDoesNotSplit() {
			AnswerGuard guard = guard();
			List<String> passed = guard.accept("1. Ask about your 162 result.\n\n");

			assertThat(guard.stopped()).isFalse();
			assertThat(passed).hasSize(1);
		}
	}

	@Test
	@DisplayName("the stop notice says what happened in plain words")
	void stopNoticeIsReadable() {
		// It reaches the reader as the last token event, so it has to make sense to someone who
		// has never heard of a number guard.
		assertThat(AnswerGuard.STOP_NOTICE)
				.contains("numbers that are on your report")
				.contains("stopped");
	}

	private static AnswerGuard guard() {
		return new AnswerGuard(LIPID_REPORT);
	}
}
