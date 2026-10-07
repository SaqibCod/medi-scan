package dev.saq.mediscan.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.support.PostgresTestBase;

/**
 * The daily counters ({@code LLD} 14).
 *
 * <p>Two things worth proving. Upserts must not lose a count when two workers finish at once -
 * a read-then-write would, and the dashboard would quietly under-report. And the tables must
 * hold nothing but counts, because they outlive every report they counted.
 */
class StatsRecorderTest extends PostgresTestBase {

	@Autowired
	StatsRecorder stats;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void reset() {
		jdbc.sql("delete from daily_stats").update();
		jdbc.sql("delete from daily_failure").update();
		jdbc.sql("delete from daily_processing").update();
	}

	@Test
	@DisplayName("counts accepted reports")
	void countsReportsCreated() {
		stats.recordReportCreated();
		stats.recordReportCreated();

		assertThat(dailyStat("reports_created")).isEqualTo(2);
	}

	@Test
	@DisplayName("counts token usage from both directions")
	void countsTokens() {
		stats.recordTokens(1200, 180);
		stats.recordTokens(800, 120);

		assertThat(dailyStat("input_tokens")).isEqualTo(2000);
		assertThat(dailyStat("output_tokens")).isEqualTo(300);
	}

	@Test
	@DisplayName("ignores a zero token report rather than writing a row")
	void ignoresZeroTokens() {
		stats.recordTokens(0, 0);

		assertThat(rowCount("daily_stats")).isZero();
	}

	@Test
	@DisplayName("counts masking conflicts, which are the ones worth noticing")
	void countsMaskingConflicts() {
		// A non-zero figure means a masking rule reached into a lab value - the one masking
		// failure that changes a result.
		stats.maskingCompleted(3);
		stats.maskingCompleted(1);

		assertThat(dailyStat("masking_conflicts")).isEqualTo(4);
	}

	@Test
	@DisplayName("a clean report writes no conflict row")
	void ignoresZeroConflicts() {
		stats.maskingCompleted(0);

		assertThat(rowCount("daily_stats")).isZero();
	}

	@Test
	@DisplayName("counts rate limit rejections")
	void countsRateLimitRejections() {
		stats.recordRateLimitRejection();

		assertThat(dailyStat("rate_limit_rejections")).isEqualTo(1);
	}

	@Test
	@DisplayName("a completed job records processing time per source type")
	void recordsProcessingTime() {
		stats.jobCompleted(SourceType.PDF, 1500);
		stats.jobCompleted(SourceType.PDF, 2500);
		stats.jobCompleted(SourceType.SAMPLE, 500);

		// A total and a count, not an average: the dashboard divides, and two writers cannot
		// corrupt a running mean.
		assertThat(processingTotal(SourceType.PDF)).isEqualTo(4000);
		assertThat(processingCount(SourceType.PDF)).isEqualTo(2);
		assertThat(processingTotal(SourceType.SAMPLE)).isEqualTo(500);
	}

	@Test
	@DisplayName("a failed job counts the failure, its code, and its time")
	void recordsFailure() {
		stats.jobFailed(SourceType.PDF, ReportErrorCode.UNREADABLE, 300);

		assertThat(dailyStat("reports_failed")).isEqualTo(1);
		assertThat(failureCount("UNREADABLE")).isEqualTo(1);
		// Failures take time too, and leaving them out would flatter the averages.
		assertThat(processingTotal(SourceType.PDF)).isEqualTo(300);
	}

	@Test
	@DisplayName("counts failures separately by code")
	void countsFailuresByCode() {
		stats.jobFailed(SourceType.PDF, ReportErrorCode.UNREADABLE, 100);
		stats.jobFailed(SourceType.PDF, ReportErrorCode.UNREADABLE, 100);
		stats.jobFailed(SourceType.TEXT, ReportErrorCode.NO_RESULTS_FOUND, 100);

		assertThat(failureCount("UNREADABLE")).isEqualTo(2);
		assertThat(failureCount("NO_RESULTS_FOUND")).isEqualTo(1);
	}

	@Test
	@DisplayName("records a batch of interrupted reports in one call")
	void recordsFailureBatch() {
		// What startup recovery does, so a restart shows up as INTERRUPTED rather than as a
		// mysterious gap.
		stats.recordFailureCode(ReportErrorCode.INTERRUPTED, 7);

		assertThat(failureCount("INTERRUPTED")).isEqualTo(7);
	}

	@Test
	@DisplayName("concurrent writes lose no counts")
	void concurrentWritesAreSafe() throws Exception {
		List<Callable<Void>> writes = IntStream.range(0, 40)
				.mapToObj(i -> (Callable<Void>) () -> {
					stats.recordReportCreated();
					stats.recordTokens(10, 5);
					return null;
				})
				.toList();

		try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
			pool.invokeAll(writes);
		}

		// An upsert, not a read-then-write. The latter would drop counts here and the
		// dashboard would quietly under-report.
		assertThat(dailyStat("reports_created")).isEqualTo(40);
		assertThat(dailyStat("input_tokens")).isEqualTo(400);
		assertThat(dailyStat("output_tokens")).isEqualTo(200);
	}

	@Test
	@DisplayName("the stats tables hold no ids and no content")
	void tablesHoldOnlyCounts() {
		stats.recordReportCreated();
		stats.jobFailed(SourceType.PDF, ReportErrorCode.UNREADABLE, 100);

		// These rows are kept for 90 days, outliving every report they counted, which is only
		// acceptable because there is nothing in them to identify anyone (plan section 7).
		assertThat(columnsOf("daily_stats"))
				.containsExactlyInAnyOrder("day", "input_tokens", "output_tokens",
						"reports_created", "reports_failed", "rate_limit_rejections",
						"masking_conflicts");
		assertThat(columnsOf("daily_failure")).containsExactlyInAnyOrder("day", "code", "count");
		assertThat(columnsOf("daily_processing"))
				.containsExactlyInAnyOrder("day", "source_type", "total_ms", "count");
	}

	@Test
	@DisplayName("deleting by cutoff clears all three tables")
	void deletesOlderThan() {
		jdbc.sql("insert into daily_stats (day, reports_created) values (current_date - 200, 1)")
				.update();
		jdbc.sql("insert into daily_failure (day, code, count) "
				+ "values (current_date - 200, 'UNREADABLE', 1)").update();
		jdbc.sql("insert into daily_processing (day, source_type, total_ms, count) "
				+ "values (current_date - 200, 'PDF', 1, 1)").update();

		int deleted = stats.deleteOlderThan(java.time.LocalDate.now().minusDays(90));

		assertThat(deleted).isEqualTo(3);
	}

	private long dailyStat(String column) {
		Long value = jdbc.sql("select " + column + " from daily_stats")
				.query(Long.class).optional().orElse(0L);
		return value;
	}

	private long processingTotal(SourceType sourceType) {
		return jdbc.sql("select total_ms from daily_processing where source_type = :type")
				.param("type", sourceType.name())
				.query(Long.class).optional().orElse(0L);
	}

	private int processingCount(SourceType sourceType) {
		return jdbc.sql("select count from daily_processing where source_type = :type")
				.param("type", sourceType.name())
				.query(Integer.class).optional().orElse(0);
	}

	private int failureCount(String code) {
		return jdbc.sql("select count from daily_failure where code = :code")
				.param("code", code)
				.query(Integer.class).optional().orElse(0);
	}

	private int rowCount(String table) {
		return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
	}

	private List<String> columnsOf(String table) {
		return jdbc.sql("select column_name from information_schema.columns "
				+ "where table_name = :table and table_schema = 'public'")
				.param("table", table)
				.query(String.class)
				.list();
	}
}
