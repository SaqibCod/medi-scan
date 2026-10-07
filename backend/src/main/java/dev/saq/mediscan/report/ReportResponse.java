package dev.saq.mediscan.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import dev.saq.mediscan.config.ReportErrorCode;

/**
 * The {@code GET /api/reports/{id}} body (contract section 4.2).
 *
 * <p>One shape for all four statuses, with {@code error} and {@code result} null when they do
 * not apply. The contract is explicit that these fields are present and null rather than
 * absent, so the client can read {@code response.result} without checking for the key - which
 * is why they are declared here and not assembled conditionally.
 *
 * <p>A failed report is still a {@code 200}: the request succeeded, the processing did not.
 */
public record ReportResponse(
		UUID id,
		ReportStatus status,
		SourceType sourceType,
		Instant createdAt,
		Instant expiresAt,
		ReportError error,
		ReportResult result) {

	/**
	 * Why a report failed.
	 *
	 * <p>{@code code} is the stable field the client switches on; {@code message} is the
	 * suggested wording from contract section 8.2, which the client may replace.
	 */
	public record ReportError(ReportErrorCode code, String message) {

		static ReportError from(ReportErrorCode code) {
			return new ReportError(code, code.message());
		}
	}

	/**
	 * The results of a finished report.
	 *
	 * @param collectedOn the specimen collection date, or null when the report printed none
	 * @param counts a tally by flag, so the client need not derive it
	 */
	public record ReportResult(
			LocalDate collectedOn,
			List<Biomarker> biomarkers,
			String summary,
			List<String> highlights,
			Counts counts) {
	}

	/**
	 * One result row.
	 *
	 * <p>{@code numericValue}, {@code refLow}, {@code refHigh} and {@code flag} were all
	 * computed in code. {@code rawValue} and {@code referenceRangeText} are exactly as printed,
	 * which is why the client renders a qualitative value as text rather than on a range bar.
	 */
	public record Biomarker(
			UUID id,
			String testName,
			String rawValue,
			Double numericValue,
			String unit,
			String referenceRangeText,
			Double refLow,
			Double refHigh,
			Flag flag,
			String biomarkerSlug) {
	}

	/** The flag tally. {@code total} is every row, including {@code unknown}. */
	public record Counts(int total, int low, int normal, int high, int unknown) {
	}
}
