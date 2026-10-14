package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Hand-made inputs, per {@code docs/phase-3-lld.md} section 9, item 3. */
class ReadingLevelTest {

	@Test
	void shortPlainWordsScoreALowGrade() {
		String text = "Your test is good. Your value is low. Ask your doctor.";

		double grade = ReadingLevel.fleschKincaidGrade(text);

		assertThat(grade).isLessThan(6.0);
	}

	@Test
	void longMedicalWordsPushTheGradeUp() {
		String plain = "Your test is high. It is above normal. Ask your doctor.";
		String medical =
				"Your hypoalbuminemia indicates malnutrition. It requires further evaluation. "
						+ "Ask your doctor.";

		double plainGrade = ReadingLevel.fleschKincaidGrade(plain);
		double medicalGrade = ReadingLevel.fleschKincaidGrade(medical);

		assertThat(medicalGrade).isGreaterThan(plainGrade);
	}

	@Test
	void blankTextScoresZero() {
		assertThat(ReadingLevel.fleschKincaidGrade("")).isEqualTo(0.0);
		assertThat(ReadingLevel.fleschKincaidGrade(null)).isEqualTo(0.0);
	}

	@Test
	void singleSyllableWordsCountAsOne() {
		assertThat(ReadingLevel.countSyllables("test")).isEqualTo(1);
		assertThat(ReadingLevel.countSyllables("high")).isEqualTo(1);
		assertThat(ReadingLevel.countSyllables("low")).isEqualTo(1);
	}

	@Test
	void aSilentTrailingEIsNotCountedAsItsOwnSyllable() {
		// "like" has vowel groups "i" and "e"; the trailing silent e should not count twice.
		assertThat(ReadingLevel.countSyllables("like")).isEqualTo(1);
	}

	@Test
	void aWordEndingInLeKeepsTheTrailingEAsASyllable() {
		// "table" is two syllables, and the "-le" ending is the second one.
		assertThat(ReadingLevel.countSyllables("table")).isEqualTo(2);
	}

	@Test
	void multiSyllableMedicalWordsCountMoreThanOne() {
		assertThat(ReadingLevel.countSyllables("cholesterol")).isGreaterThanOrEqualTo(3);
	}

	@Test
	void gradeIsAPlausibleNumberNotNegativeInfinity() {
		double grade = ReadingLevel.fleschKincaidGrade(
				"Your cholesterol is a bit high. Your HDL is a bit low. "
						+ "A doctor or nurse can explain what this means for you.");

		assertThat(grade).isCloseTo(6.0, within(5.0));
	}
}
