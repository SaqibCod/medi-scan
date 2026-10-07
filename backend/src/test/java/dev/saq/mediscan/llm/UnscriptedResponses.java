package dev.saq.mediscan.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.saq.mediscan.analysis.ModelExtraction;
import dev.saq.mediscan.analysis.ModelSummary;

/**
 * Plausible answers for {@link FakeLlmProvider} when nothing is scripted.
 *
 * <p>Used only under the {@code local} profile, so the application can be driven by hand
 * without an API key - which is what makes {@code LLM_PROVIDER=fake} a usable development
 * setting rather than only a test fixture.
 *
 * <p>It transcribes rather than invents. The rows it returns are read off the masked text it
 * was given, which means the real validator still does real work: the support check passes
 * because the values genuinely are in the text, the numbers are parsed in code, and the flags
 * are computed in code. A fake that returned hard-coded rows would sail past all of that and
 * prove nothing about a hand-driven run.
 *
 * <p>Deliberately crude - it is a stand-in for a model, not a second extraction engine. Rows it
 * cannot parse are simply not returned, and the pipeline then reports what it found.
 */
final class UnscriptedResponses {

	/**
	 * A result row: a name, a value, an optional unit, an optional range, an optional flag.
	 *
	 * <p>Anchored on two or more spaces between the name and the value, because that column gap
	 * is what distinguishes a result row from a sentence.
	 */
	private static final Pattern RESULT_ROW = Pattern.compile(
			"^(?<name>\\S.*?\\S)\\s{2,}"
					+ "(?<value>[<>≤≥]?\\s?[\\d.,]+|Negative|Positive|Trace|Reactive|Not detected)"
					+ "(?:\\s{2,}(?<unit>\\S+))?"
					+ "(?:\\s{2,}(?<range>[<>≤≥]?\\s?[\\d.,]+(?:\\s*-\\s*[\\d.,]+)?"
					+ "|Negative|Not detected))?"
					+ "(?:\\s{2,}(?<flag>H|L|HIGH|LOW))?\\s*$",
			Pattern.CASE_INSENSITIVE);

	/** Lines that look like a row but are not one. */
	private static final Pattern HEADER_ROW = Pattern.compile(
			"^\\s*(?:TEST|RESULT|UNIT|REFERENCE|FLAG)\\b", Pattern.CASE_INSENSITIVE);

	private UnscriptedResponses() {
	}

	/** An answer of the right type for {@code request}. */
	@SuppressWarnings("unchecked")
	static <T> T forPurpose(LlmRequest request, Class<T> outputType) {
		if (outputType == ModelExtraction.class) {
			return (T) extractFrom(request.userContent());
		}
		if (outputType == ModelSummary.class) {
			return (T) summaryFor(request.userContent());
		}
		throw new IllegalStateException(
				"FakeLlmProvider cannot synthesize a " + outputType.getSimpleName()
						+ "; script it explicitly");
	}

	/** Reads the result rows out of the delimited report text. */
	private static ModelExtraction extractFrom(String userContent) {
		List<ModelExtraction.ModelRow> rows = new ArrayList<>();

		for (String line : userContent.lines().toList()) {
			if (line.isBlank() || line.startsWith("<") || HEADER_ROW.matcher(line).find()) {
				continue;
			}

			Matcher matcher = RESULT_ROW.matcher(line);
			if (!matcher.matches()) {
				continue;
			}

			String name = matcher.group("name").strip();
			// Header-block lines end in a colon and are not results.
			if (name.endsWith(":") || name.contains(":")) {
				continue;
			}

			rows.add(new ModelExtraction.ModelRow(
					name,
					normalize(matcher.group("value")),
					normalize(matcher.group("unit")),
					normalize(matcher.group("range"))));
		}

		return new ModelExtraction(rows, findCollectedOn(userContent));
	}

	/** The date printed next to a collection label, transcribed exactly. */
	private static String findCollectedOn(String userContent) {
		Matcher matcher = Pattern.compile(
				"(?i)collected\\s*:?\\s*([0-9A-Za-z/.-]+(?:\\s+[A-Za-z]{3,}\\s+\\d{2,4})?)")
				.matcher(userContent);

		return matcher.find() ? matcher.group(1).strip() : null;
	}

	/**
	 * A summary that names whatever the results list says is out of range.
	 *
	 * <p>Reads the flags out of the JSON it was given rather than guessing, so the highlight
	 * filter in {@code SummaryStep} has something real to filter and a local run shows the
	 * same highlights a real model would be asked for.
	 */
	private static ModelSummary summaryFor(String userContent) {
		List<String> highlights = new ArrayList<>();

		Matcher matcher = Pattern.compile(
				"\"testName\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"flag\"\\s*:\\s*\"(LOW|HIGH)\"")
				.matcher(userContent);

		while (matcher.find()) {
			highlights.add(matcher.group(1) + " is "
					+ (matcher.group(2).equals("HIGH") ? "above" : "below")
					+ " the range printed on your report.");
		}

		String summary = highlights.isEmpty()
				? "Your results are all within the ranges printed on this report. "
						+ "A doctor or nurse can explain what they mean for you."
				: "Some of your results are outside the ranges printed on this report. "
						+ "A doctor or nurse can explain what they mean for you.";

		return new ModelSummary(summary, highlights);
	}

	private static String normalize(String group) {
		if (group == null) {
			return null;
		}
		String value = group.replaceAll("\\s+", "").strip();
		return value.isEmpty() ? null : value;
	}
}
