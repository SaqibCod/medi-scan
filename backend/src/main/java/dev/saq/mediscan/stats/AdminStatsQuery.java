package dev.saq.mediscan.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.SqlTime;

/**
 * Read-only queries behind {@code GET /api/admin/stats} (contract section 6, LLD 7).
 *
 * <p>Every query reads an aggregate table or a plain count - nothing here touches a report,
 * its text, or an account's identity, which is what lets the response hold counts only.
 */
@Component
public class AdminStatsQuery {

	private final JdbcClient jdbc;

	public AdminStatsQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<LlmUsageRow> llmUsage(LocalDate from, LocalDate to) {
		return jdbc.sql("SELECT day, calls FROM llm_usage WHERE day BETWEEN :from AND :to")
				.param("from", from)
				.param("to", to)
				.query((rs, rowNum) -> new LlmUsageRow(rs.getObject("day", LocalDate.class), rs.getLong("calls")))
				.list();
	}

	public List<DailyStatsRow> dailyStats(LocalDate from, LocalDate to) {
		return jdbc.sql("""
				SELECT day, input_tokens, output_tokens, reports_created, reports_failed,
				       rate_limit_rejections, masking_conflicts
				  FROM daily_stats
				 WHERE day BETWEEN :from AND :to
				""")
				.param("from", from)
				.param("to", to)
				.query((rs, rowNum) -> new DailyStatsRow(
						rs.getObject("day", LocalDate.class),
						rs.getLong("input_tokens"),
						rs.getLong("output_tokens"),
						rs.getLong("reports_created"),
						rs.getLong("reports_failed"),
						rs.getLong("rate_limit_rejections"),
						rs.getLong("masking_conflicts")))
				.list();
	}

	public List<FailureRow> failuresByCode(LocalDate from, LocalDate to) {
		return jdbc.sql("""
				SELECT code, SUM(count) AS total
				  FROM daily_failure
				 WHERE day BETWEEN :from AND :to
				 GROUP BY code
				""")
				.param("from", from)
				.param("to", to)
				.query((rs, rowNum) -> new FailureRow(rs.getString("code"), rs.getLong("total")))
				.list();
	}

	/** Rows with no counted minutes are left out: a source with no data is absent (LLD D3). */
	public List<ProcessingRow> avgProcessingMsBySource(LocalDate from, LocalDate to) {
		return jdbc.sql("""
				SELECT source_type, SUM(total_ms) AS total_ms, SUM(count) AS cnt
				  FROM daily_processing
				 WHERE day BETWEEN :from AND :to
				 GROUP BY source_type
				HAVING SUM(count) > 0
				""")
				.param("from", from)
				.param("to", to)
				.query((rs, rowNum) -> new ProcessingRow(
						rs.getString("source_type"), rs.getLong("total_ms"), rs.getLong("cnt")))
				.list();
	}

	public long totalUsers() {
		Long count = jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
		return count != null ? count : 0;
	}

	public long activeUsersSince(Instant since) {
		Long count = jdbc.sql("SELECT count(*) FROM app_user WHERE last_login_at >= :since")
				.param("since", SqlTime.utc(since))
				.query(Long.class)
				.single();
		return count != null ? count : 0;
	}

	public ActiveReportCounts activeReports(Instant now) {
		return jdbc.sql("""
				SELECT count(*) FILTER (WHERE session_id IS NOT NULL) AS guest_count,
				       count(*) FILTER (WHERE user_id IS NOT NULL) AS user_count
				  FROM report
				 WHERE expires_at > :now
				""")
				.param("now", SqlTime.utc(now))
				.query((rs, rowNum) -> new ActiveReportCounts(rs.getLong("guest_count"), rs.getLong("user_count")))
				.single();
	}

	public record LlmUsageRow(LocalDate day, long calls) {
	}

	public record DailyStatsRow(LocalDate day, long inputTokens, long outputTokens, long reportsCreated,
			long reportsFailed, long rateLimitRejections, long maskingConflicts) {
	}

	public record FailureRow(String code, long total) {
	}

	public record ProcessingRow(String sourceType, long totalMs, long count) {
	}

	public record ActiveReportCounts(long guest, long user) {
	}
}
