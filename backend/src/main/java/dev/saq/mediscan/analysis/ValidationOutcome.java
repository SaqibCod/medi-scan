package dev.saq.mediscan.analysis;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What validation produced, and what it threw away.
 *
 * <p>The drop counts are kept because they are the only visibility into how well extraction is
 * working. A report that quietly loses half its rows looks identical to a short report unless
 * the reasons are counted, and these numbers are what phase 3's eval will score against.
 *
 * @param biomarkers the rows that survived, in report order
 * @param collectedOn the collection date, or null
 * @param dropsByReason how many rows each reason removed, for logging as numbers only
 */
public record ValidationOutcome(
		List<ValidatedBiomarker> biomarkers,
		LocalDate collectedOn,
		Map<DropReason, Integer> dropsByReason) {

	public ValidationOutcome {
		biomarkers = List.copyOf(biomarkers);
		dropsByReason = Map.copyOf(dropsByReason);
	}

	public boolean isEmpty() {
		return biomarkers.isEmpty();
	}

	public int totalDropped() {
		return dropsByReason.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** Why a row the model returned did not make it into the report. */
	public enum DropReason {

		/** A blank or over-long name or value. */
		MALFORMED,

		/**
		 * The value does not appear in the masked source text.
		 *
		 * <p>The important one: this is the count of values the model invented or altered
		 * ({@code CLAUDE.md} rule 4). A non-zero figure here is worth investigating.
		 */
		UNSUPPORTED,

		/** The same test, value and unit as an earlier row. */
		DUPLICATE,

		/** Past the per-report row cap. */
		OVER_LIMIT
	}

	/** Counts only - never the rows. */
	@Override
	public String toString() {
		return "ValidationOutcome[kept=" + biomarkers.size() + ", dropped=" + dropsByReason
				+ ", hasCollectedOn=" + (collectedOn != null) + "]";
	}
}
