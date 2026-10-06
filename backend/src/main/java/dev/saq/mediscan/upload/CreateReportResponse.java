package dev.saq.mediscan.upload;

import java.time.Instant;
import java.util.UUID;

import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportStatus;
import dev.saq.mediscan.report.SourceType;

/**
 * The {@code 202 Accepted} body of {@code POST /api/reports} (contract section 4.1).
 *
 * <p>Also the base shape {@code GET /api/reports/{id}} extends, which is why it holds no
 * result or error fields of its own.
 */
public record CreateReportResponse(
		UUID id,
		ReportStatus status,
		SourceType sourceType,
		Instant createdAt,
		Instant expiresAt) {

	public static CreateReportResponse from(ReportEntity report) {
		return new CreateReportResponse(
				report.getId(),
				report.getStatus(),
				report.getSourceType(),
				report.getCreatedAt(),
				report.getExpiresAt());
	}
}
