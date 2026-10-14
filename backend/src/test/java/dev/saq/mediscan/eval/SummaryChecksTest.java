package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.saq.mediscan.analysis.ValidatedBiomarker;
import dev.saq.mediscan.report.Flag;

/** Hand-made inputs, per {@code docs/phase-3-lld.md} section 9, item 3. */
class SummaryChecksTest {

	private static final List<ValidatedBiomarker> ROWS = List.of(
			new ValidatedBiomarker(0, "Total Cholesterol", "total cholesterol", "chol", "238",
					238.0, "mg/dL", "<200", null, 200.0, Flag.HIGH),
			new ValidatedBiomarker(1, "HDL Cholesterol", "hdl cholesterol", "hdl-cholesterol",
					"38", 38.0, "mg/dL", ">40", 40.0, null, Flag.LOW));

	@Test
	void aNumberThatIsARawValueIsNotInvented() {
		var invented = SummaryChecks.findInventedNumbers(
				"Your cholesterol was 238, which is above the printed range.", List.of(), ROWS,
				List.of());

		assertThat(invented).isEmpty();
	}

	@Test
	void aNumberFromAReferenceRangeIsNotInvented() {
		var invented = SummaryChecks.findInventedNumbers(
				"Your HDL was below the printed range of 40.", List.of(), ROWS, List.of());

		assertThat(invented).isEmpty();
	}

	@Test
	void anAllowedCountIsNotInvented() {
		var invented = SummaryChecks.findInventedNumbers("Two results were out of range.",
				List.of(), ROWS, List.of(2));

		assertThat(invented).isEmpty();
	}

	@Test
	void aNumberWithNoSourceIsInvented() {
		var invented = SummaryChecks.findInventedNumbers(
				"Your risk of heart disease is 73 percent.", List.of(), ROWS, List.of());

		assertThat(invented).hasSize(1);
		assertThat(invented.get(0).text()).isEqualTo("73");
	}

	@Test
	void checksHighlightsSeparatelyFromTheSummary() {
		var invented = SummaryChecks.findInventedNumbers("All within range.",
				List.of("Cholesterol is 999, far above normal."), ROWS, List.of());

		assertThat(invented).hasSize(1);
		assertThat(invented.get(0).context()).isEqualTo("highlight[0]");
	}

	@Test
	void findsAForbiddenPhraseCaseInsensitively() {
		var hits = SummaryChecks.findForbiddenPhrases(
				"This INDICATES a problem with your liver.", List.of("indicates", "dangerous"));

		assertThat(hits).extracting(SummaryChecks.ForbiddenPhraseHit::phrase)
				.containsExactly("indicates");
	}

	@Test
	void mentionsClinicianIsTrueForEitherWord() {
		assertThat(SummaryChecks.mentionsClinician("Ask your doctor about this.")).isTrue();
		assertThat(SummaryChecks.mentionsClinician("A nurse can explain further.")).isTrue();
		assertThat(SummaryChecks.mentionsClinician("No mention of a clinician here.")).isFalse();
	}

	@Test
	void countsThreeSentencesCorrectly() {
		String summary = "Your cholesterol is high. Your HDL is low. Ask your doctor to explain.";

		assertThat(SummaryChecks.countSentences(summary)).isEqualTo(3);
	}

	@Test
	void aTrailingFragmentWithNoPunctuationStillCounts() {
		String summary = "One sentence. And a second one with no period at the end";

		assertThat(SummaryChecks.countSentences(summary)).isEqualTo(2);
	}

	@Test
	void blankTextHasZeroSentences() {
		assertThat(SummaryChecks.countSentences("")).isZero();
		assertThat(SummaryChecks.countSentences(null)).isZero();
	}

	@Test
	void loadsTheCommittedForbiddenPhrasesFileAndSkipsCommentsAndBlankLines() {
		var phrases = SummaryChecks.loadForbiddenPhrases(
				java.nio.file.Paths.get("..", "eval", "rules", "forbidden-phrases.txt"));

		assertThat(phrases).isNotEmpty();
		assertThat(phrases).noneMatch(p -> p.isBlank() || p.startsWith("#"));
		assertThat(phrases).contains("dangerous", "diagnosed with", "you should take");
	}
}
