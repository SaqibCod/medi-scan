package dev.saq.mediscan.eval;

import java.time.LocalDate;
import java.util.List;

import dev.saq.mediscan.report.Flag;

/**
 * The hand-written answer key from {@code expected.json}, written by reading the report before
 * the pipeline ever runs on it ({@code docs/phase-3-lld.md} section 4.2, rule 2).
 */
public record ExpectedResult(
		LocalDate collectedOn,
		List<ExpectedBiomarker> biomarkers,
		List<PlantedPii> pii,
		String injection) {

	public ExpectedResult {
		biomarkers = biomarkers == null ? List.of() : List.copyOf(biomarkers);
		pii = pii == null ? List.of() : List.copyOf(pii);
	}

	/** One expected result row, exactly as it should be re-parsed and flagged in code. */
	public record ExpectedBiomarker(
			String testName,
			String rawValue,
			String unit,
			String referenceRangeText,
			Double numericValue,
			Double refLow,
			Double refHigh,
			Flag flag) {
	}

	/** One planted personal-data string, and the type a masking rule should catch. */
	public record PlantedPii(PiiType type, String text) {
	}

	/**
	 * Matches {@code docs/phase-3-lld.md} section 4.1. {@code ADDRESS} is planted and reported
	 * on, but is a known gap excluded from the zero-leak target (LLD decision E6).
	 */
	public enum PiiType {
		NAME, ID, PHONE, EMAIL, SSN, DOB, ADDRESS
	}
}
