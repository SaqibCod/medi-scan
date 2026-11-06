package dev.saq.mediscan.support;

import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Daily counter rows for admin stats tests, for an explicit day rather than "today".
 *
 * <p>{@link dev.saq.mediscan.stats.StatsRecorder} always writes against the clock's current
 * UTC day, which makes it awkward for a test that needs several distinct days of history in
 * one run. These tables hold counts only - no owner, no report content - so writing them
 * directly with bound parameters against the real schema is still "the real persistence path"
 * ({@code backend/CLAUDE.md}, "Testing"), just for a day the test chooses.
 */
@Component
public class StatsFixtures {

	private final JdbcClient jdbc;

	public StatsFixtures(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void llmUsage(LocalDate day, int calls) {
		jdbc.sql("""
				INSERT INTO llm_usage (day, calls) VALUES (:day, :calls)
				ON CONFLICT (day) DO UPDATE SET calls = excluded.calls
				""")
				.param("day", day)
				.param("calls", calls)
				.update();
	}

	public void dailyStats(LocalDate day, long inputTokens, long outputTokens, int reportsCreated,
			int reportsFailed, int rateLimitRejections, int maskingConflicts) {
		jdbc.sql("""
				INSERT INTO daily_stats
				    (day, input_tokens, output_tokens, reports_created, reports_failed,
				     rate_limit_rejections, masking_conflicts)
				VALUES (:day, :inputTokens, :outputTokens, :reportsCreated, :reportsFailed,
				        :rateLimitRejections, :maskingConflicts)
				ON CONFLICT (day) DO UPDATE SET
				    input_tokens = excluded.input_tokens,
				    output_tokens = excluded.output_tokens,
				    reports_created = excluded.reports_created,
				    reports_failed = excluded.reports_failed,
				    rate_limit_rejections = excluded.rate_limit_rejections,
				    masking_conflicts = excluded.masking_conflicts
				""")
				.param("day", day)
				.param("inputTokens", inputTokens)
				.param("outputTokens", outputTokens)
				.param("reportsCreated", reportsCreated)
				.param("reportsFailed", reportsFailed)
				.param("rateLimitRejections", rateLimitRejections)
				.param("maskingConflicts", maskingConflicts)
				.update();
	}

	public void failure(LocalDate day, String code, int count) {
		jdbc.sql("""
				INSERT INTO daily_failure (day, code, count) VALUES (:day, :code, :count)
				ON CONFLICT (day, code) DO UPDATE SET count = excluded.count
				""")
				.param("day", day)
				.param("code", code)
				.param("count", count)
				.update();
	}

	public void processing(LocalDate day, String sourceType, long totalMs, int count) {
		jdbc.sql("""
				INSERT INTO daily_processing (day, source_type, total_ms, count)
				VALUES (:day, :sourceType, :totalMs, :count)
				ON CONFLICT (day, source_type) DO UPDATE SET
				    total_ms = excluded.total_ms, count = excluded.count
				""")
				.param("day", day)
				.param("sourceType", sourceType)
				.param("totalMs", totalMs)
				.param("count", count)
				.update();
	}

	/** Deletes every row from all four tables, for test isolation. */
	public void clear() {
		jdbc.sql("DELETE FROM llm_usage").update();
		jdbc.sql("DELETE FROM daily_stats").update();
		jdbc.sql("DELETE FROM daily_failure").update();
		jdbc.sql("DELETE FROM daily_processing").update();
	}
}
