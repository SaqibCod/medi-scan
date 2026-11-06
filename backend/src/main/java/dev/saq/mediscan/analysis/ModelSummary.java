package dev.saq.mediscan.analysis;

import java.util.List;

/**
 * What step 2 asks the model for.
 *
 * <p>The model producing this never saw the report: step 2 receives the validated value list
 * and nothing else ({@code CLAUDE.md} rule 5). That is what makes it impossible for the
 * summary to contradict the flags, and what keeps the raw text out of a second prompt.
 *
 * @param summary the patient-facing text
 * @param highlights one sentence per out-of-range marker, filtered again in code
 */
public record ModelSummary(String summary, List<String> highlights) {

	public ModelSummary {
		highlights = highlights == null ? List.of() : List.copyOf(highlights);
	}

	/** Hides the text, which quotes values and ranges. */
	@Override
	public String toString() {
		return "ModelSummary[summaryChars=" + (summary != null ? summary.length() : 0)
				+ ", highlights=" + highlights.size() + "]";
	}
}
