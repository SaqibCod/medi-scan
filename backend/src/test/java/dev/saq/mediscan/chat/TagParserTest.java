package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reading the decision tag ({@code LLD} 6.3, 13).
 *
 * <p>The case that matters most is the split tag, and it is not hypothetical: a live
 * gemini-3.6-flash stream on 2026-11-03 opened with {@code "[[ANSWER"} and continued
 * {@code "]] Your LDL cholesterol is..."}. A parser that inspected each chunk on its own would
 * have decided there was no tag and shipped {@code "]] Your LDL..."} to the reader.
 */
class TagParserTest {

	@Nested
	@DisplayName("recognising the tag")
	class Recognising {

		@Test
		@DisplayName("a tag inside one chunk is read and removed")
		void wholeTagInOneChunk() {
			TagParser parser = new TagParser();

			assertThat(parser.accept("[[ANSWER]] Your LDL is 162 mg/dL."))
					.isEqualTo("Your LDL is 162 mg/dL.");
			assertThat(parser.decision()).isEqualTo(TagParser.Decision.ANSWER);
			assertThat(parser.tagWasMissing()).isFalse();
		}

		@Test
		@DisplayName("a tag split across chunks is still read")
		void splitTag() {
			// The exact shape the live API produced.
			TagParser parser = new TagParser();

			assertThat(parser.accept("[[ANSWER")).isEmpty();
			assertThat(parser.accept("]] Your LDL cholesterol is flagged"))
					.isEqualTo("Your LDL cholesterol is flagged");
			assertThat(parser.decision()).isEqualTo(TagParser.Decision.ANSWER);
		}

		@Test
		@DisplayName("a tag split one character at a time is still read")
		void tagSplitPerCharacter() {
			TagParser parser = new TagParser();
			StringBuilder out = new StringBuilder();

			for (char c : "[[DECLINE]] Ask your doctor.".toCharArray()) {
				out.append(parser.accept(String.valueOf(c)));
			}

			assertThat(parser.decision()).isEqualTo(TagParser.Decision.DECLINE);
			assertThat(out.toString()).isEqualTo("Ask your doctor.");
		}

		@Test
		@DisplayName("each tag maps to its contract finishReason")
		void decisionsMapToFinishReasons() {
			assertThat(decisionOf("[[ANSWER]] text").finishReason()).isEqualTo("COMPLETE");
			assertThat(decisionOf("[[DECLINE]] text").finishReason()).isEqualTo("DECLINED");
			assertThat(decisionOf("[[NOT_IN_CONTEXT]] text").finishReason())
					.isEqualTo("NOT_IN_CONTEXT");
		}

		@Test
		@DisplayName("leading whitespace before the tag is tolerated")
		void leadingWhitespace() {
			TagParser parser = new TagParser();
			assertThat(parser.accept("\n  [[DECLINE]] Ask a doctor.")).isEqualTo("Ask a doctor.");
			assertThat(parser.decision()).isEqualTo(TagParser.Decision.DECLINE);
		}

		@Test
		@DisplayName("an unknown tag is treated as an answer")
		void unknownTagIsAnAnswer() {
			// Forward compatible rather than fatal: a model inventing [[MAYBE]] should still
			// produce a readable answer, and the guard still checks every number in it.
			TagParser parser = new TagParser();
			assertThat(parser.accept("[[MAYBE]] Here is the text.")).isEqualTo("Here is the text.");
			assertThat(parser.decision()).isEqualTo(TagParser.Decision.ANSWER);
		}
	}

	@Nested
	@DisplayName("when there is no tag")
	class Missing {

		@Test
		@DisplayName("text that cannot be a tag is released at once")
		void textWithoutABracketFlowsImmediately() {
			TagParser parser = new TagParser();

			// No waiting: the first character already rules a tag out.
			assertThat(parser.accept("Your LDL is high.")).isEqualTo("Your LDL is high.");
			assertThat(parser.decision()).isEqualTo(TagParser.Decision.ANSWER);
			assertThat(parser.tagWasMissing()).isTrue();
		}

		@Test
		@DisplayName("an unterminated bracket is released once the scan limit passes")
		void unterminatedBracketGivesUp() {
			TagParser parser = new TagParser();
			String long0 = "[[" + "x".repeat(TagParser.TAG_SCAN_LIMIT);

			assertThat(parser.accept(long0)).isEqualTo(long0);
			assertThat(parser.tagWasMissing()).isTrue();
		}

		@Test
		@DisplayName("a short tagless answer is flushed when the stream ends")
		void shortAnswerIsFlushedOnFinish() {
			// Shorter than the scan limit, so accept() is still waiting when the model stops.
			// Without finish() this answer would be sent as nothing at all.
			TagParser parser = new TagParser();

			assertThat(parser.accept("[no")).isEmpty();
			assertThat(parser.finish()).isEqualTo("[no");
			assertThat(parser.tagWasMissing()).isTrue();
		}

		@Test
		@DisplayName("a complete tag arriving only at the end is still read")
		void tagCompletedAtFinish() {
			TagParser parser = new TagParser();

			assertThat(parser.accept("[[DECLINE]]")).isEmpty();
			assertThat(parser.finish()).isEmpty();
			assertThat(parser.decision()).isEqualTo(TagParser.Decision.DECLINE);
			assertThat(parser.tagWasMissing()).isFalse();
		}
	}

	@Nested
	@DisplayName("repeated tags")
	class Repeats {

		@Test
		@DisplayName("a tag repeated later in the answer is removed")
		void repeatedTagIsStripped() {
			TagParser parser = new TagParser();
			parser.accept("[[ANSWER]] First part. ");

			assertThat(parser.accept("[[ANSWER]] Second part."))
					.isEqualTo(" Second part.");
		}

		@Test
		@DisplayName("bracketed text that is not a known tag survives")
		void unknownBracketsAreLeftAlone() {
			// Removing anything that merely looked like a tag would silently corrupt an answer
			// that legitimately quotes double brackets.
			TagParser parser = new TagParser();
			parser.accept("[[ANSWER]] The report says ");

			assertThat(parser.accept("[[see note]] next to it."))
					.isEqualTo("[[see note]] next to it.");
		}
	}

	private static TagParser.Decision decisionOf(String text) {
		TagParser parser = new TagParser();
		parser.accept(text);
		return parser.decision();
	}
}
