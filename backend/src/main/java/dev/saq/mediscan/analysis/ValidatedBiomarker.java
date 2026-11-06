package dev.saq.mediscan.analysis;

import dev.saq.mediscan.report.Flag;

/**
 * One result row after validation: strings from the model, everything else from code.
 *
 * <p>The split is the point. {@code testName}, {@code rawValue}, {@code unit} and
 * {@code referenceRangeText} are what the model transcribed, kept exactly as printed.
 * {@code numericValue}, {@code refLow}, {@code refHigh} and {@code flag} were all derived here
 * ({@code CLAUDE.md} rule 4), and {@code rawValue} has been checked to appear in the masked
 * source text, so the model cannot have invented it.
 *
 * @param position the order the row had on the report
 * @param testNameNorm the normalised name, for the stored column and trend matching
 * @param biomarkerSlug the curated page this links to, or null when nothing matched
 * @param numericValue null for a qualitative or comparator value
 */
public record ValidatedBiomarker(
		int position,
		String testName,
		String testNameNorm,
		String biomarkerSlug,
		String rawValue,
		Double numericValue,
		String unit,
		String referenceRangeText,
		Double refLow,
		Double refHigh,
		Flag flag) {

	/**
	 * Hides the name, value, unit and range.
	 *
	 * <p>A validated row is the result itself - the most load-bearing content in the system.
	 * Position and flag are the two fields worth having in a log line
	 * ({@code backend/CLAUDE.md} forbids logging biomarker values).
	 */
	@Override
	public String toString() {
		return "ValidatedBiomarker[position=" + position + ", flag=" + flag
				+ ", slug=" + biomarkerSlug + "]";
	}
}
