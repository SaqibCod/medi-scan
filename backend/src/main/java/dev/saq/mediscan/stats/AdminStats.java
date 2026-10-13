package dev.saq.mediscan.stats;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.report.SourceType;

/**
 * {@code GET /api/admin/stats}'s body (contract section 6, LLD 7).
 *
 * <p>Counts and averages only - no report ids, session ids, user ids, or names. That is a code
 * review check as much as a design note: every field below is a number, a date, or a map keyed
 * by an enum (LLD 7, test 30).
 */
public record AdminStats(
		LocalDate from,
		LocalDate to,
		int dailyLlmCap,
		List<DayStats> days,
		Map<ReportErrorCode, Long> failuresByCode,
		Map<SourceType, Long> avgProcessingMsBySource,
		Users users,
		ActiveReports activeReports) {

	/** One UTC day's counters, zero-filled when the day has no row in a table. */
	public record DayStats(
			LocalDate date,
			long llmCalls,
			long inputTokens,
			long outputTokens,
			long reportsCreated,
			long reportsFailed,
			long rateLimitRejections,
			long maskingConflicts) {
	}

	public record Users(long total, long activeLast7Days) {
	}

	public record ActiveReports(long guest, long user) {
	}
}
