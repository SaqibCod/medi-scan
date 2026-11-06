package dev.saq.mediscan.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import dev.saq.mediscan.config.ReportErrorCode;

/**
 * One row of {@code GET /api/reports} (contract section 4.5, LLD 5).
 *
 * <p>A summary only: no summary text, no biomarker names, no filenames. {@code counts} and
 * {@code collectedOn} are {@code null} unless {@code status} is {@code DONE}; {@code errorCode}
 * is set only for {@code FAILED}.
 */
public record ReportListItem(
		UUID id,
		ReportStatus status,
		SourceType sourceType,
		Instant createdAt,
		Instant expiresAt,
		LocalDate collectedOn,
		ReportResponse.Counts counts,
		ReportErrorCode errorCode) {
}
