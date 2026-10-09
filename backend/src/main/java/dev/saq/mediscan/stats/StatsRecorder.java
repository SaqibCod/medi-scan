package dev.saq.mediscan.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.analysis.ReportJob;
import dev.saq.mediscan.analysis.ReportJobRunner;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.llm.LlmGateway;
import dev.saq.mediscan.report.SourceType;

/**
 * Daily counters for the admin dashboard.
 *
 * <p>Counts only. No report ids, no session ids, no user ids, no content - which is what lets
 * these rows outlive the reports they counted and be kept for 90 days after everything else is
 * gone ({@code docs/plan.md} section 7).
 *
 * <p>Implements the three observer interfaces the pipeline declares, rather than being called
 * directly. {@code analysis} and {@code llm} depend on their own small interfaces, this
 * depends on them, and the dependency therefore runs one way
 * ({@code backend/CLAUDE.md}, "Dependency direction"). It also means every one of those
 * packages works with stats absent, which is how their unit tests run without a database.
 *
 * <p>Every write is an upsert, so two workers finishing at once cannot lose a count. The day
 * is the UTC date from the injected clock, matching the {@code day} primary key.
 */
@Component
public class StatsRecorder implements LlmGateway.TokenRecorder, ReportJob.PipelineObserver,
		ReportJobRunner.JobObserver {

	private static final Logger log = LoggerFactory.getLogger(StatsRecorder.class);

	private final JdbcClient jdbc;
	private final Clock clock;

	public StatsRecorder(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// --- daily_stats ---------------------------------------------------------

	/** A report was accepted. Called at upload, before any processing. */
	public void recordReportCreated() {
		addToDailyStats("reports_created", 1);
	}

	/** An upload was refused by the rate limiter. */
	public void recordRateLimitRejection() {
		addToDailyStats("rate_limit_rejections", 1);
	}

	@Override
	public void recordTokens(int inputTokens, int outputTokens) {
		if (inputTokens <= 0 && outputTokens <= 0) {
			return;
		}
		upsertDailyStats("input_tokens", inputTokens, "output_tokens", outputTokens);
	}

	@Override
	public void maskingCompleted(int conflicts) {
		if (conflicts <= 0) {
			return;
		}
		// Worth a counter of its own: a non-zero figure means a masking rule reached into a
		// lab value, which is the one masking failure that changes a result.
		addToDailyStats("masking_conflicts", conflicts);
	}

	// --- job outcomes --------------------------------------------------------

	@Override
	public void jobCompleted(SourceType sourceType, long durationMs) {
		addProcessingTime(sourceType, durationMs);
	}

	@Override
	public void jobFailed(SourceType sourceType, ReportErrorCode code, long durationMs) {
		addToDailyStats("reports_failed", 1);
		recordFailureCode(code, 1);
		addProcessingTime(sourceType, durationMs);
	}

	/** Counts failures by code, including the batch that startup recovery produces. */
	public void recordFailureCode(ReportErrorCode code, int count) {
		jdbc.sql("""
				insert into daily_failure (day, code, count)
				values (:day, :code, :count)
				on conflict (day, code) do update set count = daily_failure.count + excluded.count
				""")
				.param("day", today())
				.param("code", code.name())
				.param("count", count)
				.update();
	}

	// --- internals -----------------------------------------------------------

	/**
	 * Accumulates processing time per source type.
	 *
	 * <p>A total and a count rather than an average, so the dashboard can divide and two
	 * writers cannot corrupt a running mean.
	 */
	private void addProcessingTime(SourceType sourceType, long durationMs) {
		jdbc.sql("""
				insert into daily_processing (day, source_type, total_ms, count)
				values (:day, :sourceType, :durationMs, 1)
				on conflict (day, source_type) do update set
				    total_ms = daily_processing.total_ms + excluded.total_ms,
				    count = daily_processing.count + excluded.count
				""")
				.param("day", today())
				.param("sourceType", sourceType.name())
				.param("durationMs", durationMs)
				.update();
	}

	private void addToDailyStats(String column, int amount) {
		// The column name is a compile-time constant from this class, never user input - but
		// it is still worth saying so, because this is the only interpolated SQL in the
		// application.
		jdbc.sql("insert into daily_stats (day, " + column + ") values (:day, :amount) "
				+ "on conflict (day) do update set " + column
				+ " = daily_stats." + column + " + excluded." + column)
				.param("day", today())
				.param("amount", amount)
				.update();
	}

	private void upsertDailyStats(String firstColumn, long firstAmount,
			String secondColumn, long secondAmount) {

		jdbc.sql("insert into daily_stats (day, " + firstColumn + ", " + secondColumn + ") "
				+ "values (:day, :first, :second) on conflict (day) do update set "
				+ firstColumn + " = daily_stats." + firstColumn + " + excluded." + firstColumn
				+ ", " + secondColumn + " = daily_stats." + secondColumn
				+ " + excluded." + secondColumn)
				.param("day", today())
				.param("first", firstAmount)
				.param("second", secondAmount)
				.update();
	}

	/**
	 * Deletes counters older than the retention horizon.
	 *
	 * @return how many rows went, across the three tables
	 */
	public int deleteOlderThan(LocalDate cutoff) {
		int deleted = jdbc.sql("delete from daily_stats where day < :cutoff")
				.param("cutoff", cutoff).update();
		deleted += jdbc.sql("delete from daily_failure where day < :cutoff")
				.param("cutoff", cutoff).update();
		deleted += jdbc.sql("delete from daily_processing where day < :cutoff")
				.param("cutoff", cutoff).update();

		if (deleted > 0) {
			log.info("Retention: deleted {} stats rows older than {}", deleted, cutoff);
		}
		return deleted;
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(ZoneOffset.UTC));
	}
}
