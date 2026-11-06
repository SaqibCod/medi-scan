package dev.saq.mediscan.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An approximate Flesch-Kincaid grade level, with a simple syllable-counting heuristic
 * ({@code docs/phase-3-lld.md} section 6.5). Approximate on purpose: a real syllable counter is
 * out of scope, and medical words push the score up regardless, which is itself useful
 * information about whether the summary is written at a 6th-grade level.
 */
public final class ReadingLevel {

	private static final Pattern WORD = Pattern.compile("[A-Za-z]+(?:'[A-Za-z]+)?");
	private static final Pattern VOWEL_GROUP = Pattern.compile("[aeiouy]+");

	private ReadingLevel() {
	}

	/** {@code 0.39 * wordsPerSentence + 11.8 * syllablesPerWord - 15.59}. */
	public static double fleschKincaidGrade(String text) {
		if (text == null || text.isBlank()) {
			return 0.0;
		}

		List<String> words = wordsOf(text);
		if (words.isEmpty()) {
			return 0.0;
		}
		int sentenceCount = Math.max(1, SummaryChecks.countSentences(text));

		int syllables = words.stream().mapToInt(ReadingLevel::countSyllables).sum();

		double wordsPerSentence = (double) words.size() / sentenceCount;
		double syllablesPerWord = (double) syllables / words.size();

		return 0.39 * wordsPerSentence + 11.8 * syllablesPerWord - 15.59;
	}

	private static List<String> wordsOf(String text) {
		List<String> words = new ArrayList<>();
		Matcher matcher = WORD.matcher(text);
		while (matcher.find()) {
			words.add(matcher.group());
		}
		return words;
	}

	/** Vowel groups, with a silent trailing "e" dropped unless it's the word's only vowel. */
	static int countSyllables(String word) {
		String lower = word.toLowerCase(Locale.ROOT);
		Matcher matcher = VOWEL_GROUP.matcher(lower);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		if (lower.endsWith("e") && !lower.endsWith("le") && count > 1) {
			count--;
		}
		return Math.max(count, 1);
	}
}
