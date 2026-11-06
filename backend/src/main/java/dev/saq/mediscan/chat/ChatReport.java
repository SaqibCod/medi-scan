package dev.saq.mediscan.chat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything chat is allowed to know about one report.
 *
 * <p>A flat value object rather than the JPA entities, for three reasons. It is assembled once
 * inside the owner check, so nothing downstream can accidentally read a report it was not given
 * ({@code CLAUDE.md} rule 7). It carries only validated data - the flags here were computed in
 * code and the values were checked against the masked text, so the prompt cannot be handed a
 * number the model made up earlier in the pipeline. And it can hide its own contents from
 * {@code toString()}, which an entity graph cannot.
 *
 * @param id the report id, which is the only part of this that may be logged
 * @param maskedText the report as the model will see it - already masked, never raw
 * @param biomarkers the validated results, in the order they appear on the report
 * @param collectedOn the specimen collection date, or null when the report did not print one
 */
public record ChatReport(
		UUID id,
		String maskedText,
		List<ChatBiomarker> biomarkers,
		LocalDate collectedOn) {

	public ChatReport {
		biomarkers = List.copyOf(biomarkers);
	}

	/**
	 * One validated result.
	 *
	 * @param testName the test as printed
	 * @param rawValue the result as printed, which was verified to appear in the masked text
	 * @param unit as printed, or null
	 * @param referenceRangeText as printed, or null
	 * @param flag {@code LOW}, {@code NORMAL}, {@code HIGH} or {@code UNKNOWN}, computed in
	 *     code. The prompt tells the model to trust this rather than judge the value itself
	 * @param slug the curated page for this marker, or null when there is none
	 */
	public record ChatBiomarker(
			String testName,
			String rawValue,
			String unit,
			String referenceRangeText,
			String flag,
			String slug) {

		/** Hides the value, which is report content. */
		@Override
		public String toString() {
			return "ChatBiomarker[testName=" + testName + ", flag=" + flag + "]";
		}
	}

	/** Every number-bearing string the report printed, for {@link AllowedNumbers}. */
	public List<String> printedValues() {
		List<String> printed = new ArrayList<>(biomarkers.size() * 2);
		for (ChatBiomarker biomarker : biomarkers) {
			printed.add(biomarker.rawValue());
			if (biomarker.referenceRangeText() != null) {
				printed.add(biomarker.referenceRangeText());
			}
		}
		return printed;
	}

	/** Total, then one count per flag, which an answer may legitimately total up. */
	public List<Integer> counts() {
		return List.of(
				biomarkers.size(),
				countOf("LOW"), countOf("NORMAL"), countOf("HIGH"), countOf("UNKNOWN"));
	}

	private int countOf(String flag) {
		return (int) biomarkers.stream().filter(b -> flag.equals(b.flag())).count();
	}

	/** The slugs of markers on this report that have a curated page. */
	public List<String> slugs() {
		return biomarkers.stream()
				.map(ChatBiomarker::slug)
				.filter(slug -> slug != null && !slug.isBlank())
				.distinct()
				.toList();
	}

	/**
	 * Hides the text and the values.
	 *
	 * <p>This is the single object holding a whole report in the chat path, so it is the one
	 * most worth making unprintable ({@code CLAUDE.md} rule 3).
	 */
	@Override
	public String toString() {
		return "ChatReport[id=" + id + ", biomarkers=" + biomarkers.size()
				+ ", maskedChars=" + maskedText.length() + "]";
	}
}
