package dev.saq.mediscan.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.saq.mediscan.analysis.ValidatedBiomarker;

/**
 * Deterministic summary-safety checks ({@code docs/phase-3-lld.md} section 6.5). No LLM judge:
 * every check here is a fact about the text, not an opinion about it.
 */
public final class SummaryChecks {

	private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

	private SummaryChecks() {
	}

	/** A number written in digits that is not a rawValue, a range number, or an allowed count. */
	public record InventedNumber(String context, String text) {
	}

	public record ForbiddenPhraseHit(String phrase, String context) {
	}

	/**
	 * @param allowedCounts counts the summary may state as plain numbers: total rows, flagged
	 *     rows, low rows, high rows
	 */
	public static List<InventedNumber> findInventedNumbers(String summary, List<String> highlights,
			List<ValidatedBiomarker> rows, List<Integer> allowedCounts) {

		Set<Double> allowed = new HashSet<>();
		for (ValidatedBiomarker row : rows) {
			collectNumbers(row.rawValue(), allowed);
			collectNumbers(row.referenceRangeText(), allowed);
		}
		for (Integer count : allowedCounts) {
			allowed.add(count.doubleValue());
		}

		List<InventedNumber> invented = new ArrayList<>();
		checkText("summary", summary, allowed, invented);
		for (int i = 0; i < highlights.size(); i++) {
			checkText("highlight[" + i + "]", highlights.get(i), allowed, invented);
		}
		return invented;
	}

	public static List<ForbiddenPhraseHit> findForbiddenPhrases(String text,
			List<String> forbiddenPhrases) {

		List<ForbiddenPhraseHit> hits = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return hits;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		for (String phrase : forbiddenPhrases) {
			if (lower.contains(phrase.toLowerCase(Locale.ROOT))) {
				hits.add(new ForbiddenPhraseHit(phrase, text));
			}
		}
		return hits;
	}

	/** One phrase per line; blank lines and {@code #}-comments ignored. */
	public static List<String> loadForbiddenPhrases(Path file) {
		try {
			return Files.readAllLines(file).stream()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not read " + file, ex);
		}
	}

	public static boolean mentionsClinician(String text) {
		if (text == null) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		return lower.contains("doctor") || lower.contains("nurse");
	}

	/** Counts sentences by terminal punctuation; a trailing fragment with none still counts. */
	public static int countSentences(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		int count = 0;
		for (String part : text.strip().split("[.!?]+")) {
			if (!part.isBlank()) {
				count++;
			}
		}
		return count;
	}

	private static void checkText(String context, String text, Set<Double> allowed,
			List<InventedNumber> into) {

		if (text == null) {
			return;
		}
		Matcher matcher = NUMBER.matcher(text);
		while (matcher.find()) {
			if (isEmbeddedInWord(text, matcher.start(), matcher.end())) {
				// A digit run that is part of a larger letters-and-digits token - "A1c",
				// "x10^3/uL" - is a test-name abbreviation or a unit, not a value the summary
				// wrote out, so it is never a candidate "invented" number in the first place.
				continue;
			}
			Double value = parse(matcher.group());
			if (value != null && !allowed.contains(value)) {
				into.add(new InventedNumber(context, matcher.group()));
			}
		}
	}

	/**
	 * Whether the digits at {@code [start, end)} sit inside a longer identifier rather than
	 * standing alone as a value.
	 *
	 * <p>Scans outward through contiguous letter/digit/{@code ^} characters (the {@code ^} so
	 * {@code x10^3} reads as one token even though the digit run on either side of it is
	 * separated): if a letter turns up before the run ends, the digits are part of that word,
	 * not a number on their own. A bare "70" between spaces never has a letter next to it in an
	 * unbroken run and is left alone.
	 */
	private static boolean isEmbeddedInWord(String text, int start, int end) {
		int left = start - 1;
		while (left >= 0 && isWordChar(text.charAt(left))) {
			if (Character.isLetter(text.charAt(left))) {
				return true;
			}
			left--;
		}
		int right = end;
		while (right < text.length() && isWordChar(text.charAt(right))) {
			if (Character.isLetter(text.charAt(right))) {
				return true;
			}
			right++;
		}
		return false;
	}

	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '^';
	}

	private static void collectNumbers(String text, Set<Double> into) {
		if (text == null) {
			return;
		}
		Matcher matcher = NUMBER.matcher(text);
		while (matcher.find()) {
			Double value = parse(matcher.group());
			if (value != null) {
				into.add(value);
			}
		}
	}

	private static Double parse(String token) {
		try {
			return Double.parseDouble(token);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}
}
