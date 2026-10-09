package dev.saq.mediscan.analysis;

import java.util.List;

/**
 * What step 1 asks the model for: strings as printed, nothing more.
 *
 * <p>This is the whole schema. There is deliberately no numeric value, no bound and no flag
 * ({@code CLAUDE.md} rule 4): a model asked for a number can silently round it, and a model
 * asked for a flag can get it backwards, and either would put an unchecked claim in front of a
 * patient. Asking only for transcription means the single thing the model can get wrong is
 * what it read - and {@link BiomarkerValidator} checks that against the source text.
 *
 * @param biomarkers one entry per result row printed on the report
 * @param collectedOn the specimen collection date exactly as printed, or null
 */
public record ModelExtraction(List<ModelRow> biomarkers, String collectedOn) {

	public ModelExtraction {
		// A model that returned no list at all is the same thing as one that found no rows.
		biomarkers = biomarkers == null ? List.of() : List.copyOf(biomarkers);
	}

	/**
	 * Hides the rows.
	 *
	 * <p>The rows are the report's results, transcribed. Counts are what the logs want
	 * ({@code LLD} 16).
	 */
	@Override
	public String toString() {
		return "ModelExtraction[rows=" + biomarkers.size()
				+ ", hasCollectedOn=" + (collectedOn != null && !collectedOn.isBlank()) + "]";
	}

	/**
	 * One result row as the model read it.
	 *
	 * @param testName the test name as printed
	 * @param rawValue the result exactly as printed, comparators and words included
	 * @param unit the unit as printed, or null
	 * @param referenceRangeText the reference range as printed, or null
	 */
	public record ModelRow(String testName, String rawValue, String unit,
			String referenceRangeText) {

		/** Hides every field: all four are report content. */
		@Override
		public String toString() {
			return "ModelRow[hasName=" + (testName != null) + ", hasValue=" + (rawValue != null)
					+ "]";
		}
	}
}
