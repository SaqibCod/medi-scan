package dev.saq.mediscan.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ProblemDetails;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ValidationException;
import dev.saq.mediscan.report.SourceType;

/**
 * {@code GET /api/admin/stats} (contract section 6, LLD 7).
 *
 * <p>Reads five small aggregate tables and fills the gaps in code: a day with no row in
 * {@code daily_stats} or {@code llm_usage} is zero, not missing, so the response always has
 * exactly {@code days} entries (LLD 7, test 26).
 */
@Service
public class AdminStatsService {

	private final AdminStatsQuery query;
	private final MediScanProperties.Admin limits;
	private final int dailyLlmCap;
	private final Clock clock;

	public AdminStatsService(AdminStatsQuery query, MediScanProperties properties, Clock clock) {
		this.query = query;
		this.limits = properties.admin();
		this.dailyLlmCap = properties.llm().dailyCap();
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AdminStats stats(Integer daysParam) {
		int days = resolveDays(daysParam);

		LocalDate to = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		LocalDate from = to.minusDays(days - 1L);

		Map<LocalDate, Long> calls = new LinkedHashMap<>();
		for (AdminStatsQuery.LlmUsageRow row : query.llmUsage(from, to)) {
			calls.put(row.day(), row.calls());
		}

		Map<LocalDate, AdminStatsQuery.DailyStatsRow> dailyStats = new LinkedHashMap<>();
		for (AdminStatsQuery.DailyStatsRow row : query.dailyStats(from, to)) {
			dailyStats.put(row.day(), row);
		}

		List<AdminStats.DayStats> dayEntries = from.datesUntil(to.plusDays(1))
				.map(date -> {
					long llmCalls = calls.getOrDefault(date, 0L);
					AdminStatsQuery.DailyStatsRow row = dailyStats.get(date);
					return row == null
							? new AdminStats.DayStats(date, llmCalls, 0, 0, 0, 0, 0, 0)
							: new AdminStats.DayStats(date, llmCalls, row.inputTokens(), row.outputTokens(),
									row.reportsCreated(), row.reportsFailed(), row.rateLimitRejections(),
									row.maskingConflicts());
				})
				.toList();

		Map<ReportErrorCode, Long> failuresByCode = new LinkedHashMap<>();
		for (AdminStatsQuery.FailureRow row : query.failuresByCode(from, to)) {
			failuresByCode.put(ReportErrorCode.valueOf(row.code()), row.total());
		}

		Map<SourceType, Long> avgProcessingMsBySource = new LinkedHashMap<>();
		for (AdminStatsQuery.ProcessingRow row : query.avgProcessingMsBySource(from, to)) {
			// query guarantees count > 0 for every row it returns.
			long avg = Math.round((double) row.totalMs() / row.count());
			avgProcessingMsBySource.put(SourceType.valueOf(row.sourceType()), avg);
		}

		java.time.Instant now = java.time.Instant.now(clock);
		long totalUsers = query.totalUsers();
		long activeUsers = query.activeUsersSince(now.minus(java.time.Duration.ofDays(7)));
		AdminStatsQuery.ActiveReportCounts activeReports = query.activeReports(now);

		return new AdminStats(
				from,
				to,
				dailyLlmCap,
				dayEntries,
				failuresByCode,
				avgProcessingMsBySource,
				new AdminStats.Users(totalUsers, activeUsers),
				new AdminStats.ActiveReports(activeReports.guest(), activeReports.user()));
	}

	private int resolveDays(Integer daysParam) {
		if (daysParam == null) {
			return limits.defaultDays();
		}
		if (daysParam < 1 || daysParam > limits.maxDays()) {
			throw new ValidationException("One or more fields are invalid.",
					List.of(new ProblemDetails.FieldError("days", "must be 1 to " + limits.maxDays())));
		}
		return daysParam;
	}
}
