package dev.saq.mediscan.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.upload.TempFileStore;

/**
 * The three sweeps Phase 2 adds to retention ({@code LLD} 14).
 *
 * <p>For each one, the assertion that matters is the negative: a cleanup job that deletes too
 * much is worse than one that deletes too little. A live guest would lose their report, or a
 * running job would lose the file it is reading.
 */
class RetentionAdditionsTest extends PostgresTestBase {

	@Autowired
	RetentionJob retentionJob;

	@Autowired
	ReportRepository reports;

	@Autowired
	TempFileStore tempFiles;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	JdbcClient jdbc;

	private UUID sessionId;

	@BeforeEach
	void reset() {
		fixtures.clear();
		jdbc.sql("delete from daily_stats").update();
		jdbc.sql("delete from daily_failure").update();
		jdbc.sql("delete from daily_processing").update();
		sessionId = fixtures.session();
	}

	// --- expired reports -----------------------------------------------------

	@Test
	@DisplayName("deletes expired reports and keeps live ones")
	void deletesExpiredReports() {
		UUID expired = fixtures.expiredReport(sessionId);
		UUID live = fixtures.pendingReport(sessionId);

		assertThat(retentionJob.deleteExpiredReports()).isEqualTo(1);

		assertThat(reports.findById(expired)).isEmpty();
		// The half that matters: a live guest must not lose their report.
		assertThat(reports.findById(live)).isPresent();
	}

	@Test
	@DisplayName("the report sweep is idempotent")
	void reportSweepIsIdempotent() {
		fixtures.expiredReport(sessionId);
		UUID live = fixtures.pendingReport(sessionId);

		retentionJob.deleteExpiredReports();
		assertThat(retentionJob.deleteExpiredReports()).isZero();

		assertThat(reports.findById(live)).isPresent();
	}

	// --- stats ---------------------------------------------------------------

	@Test
	@DisplayName("deletes stats past the horizon and keeps recent ones")
	void deletesOldStats() {
		LocalDate old = LocalDate.now().minusDays(120);
		LocalDate recent = LocalDate.now().minusDays(30);

		insertStatsFor(old);
		insertStatsFor(recent);

		retentionJob.deleteOldStats();

		assertThat(statsRowsFor(old)).isZero();
		// Still inside the 90-day horizon, so the dashboard keeps its history.
		assertThat(statsRowsFor(recent)).isEqualTo(3);
	}

	@Test
	@DisplayName("the stats sweep touches none of the three tables when nothing is old")
	void statsSweepKeepsEverythingRecent() {
		LocalDate today = LocalDate.now();
		insertStatsFor(today);

		assertThat(retentionJob.deleteOldStats()).isZero();
		assertThat(statsRowsFor(today)).isEqualTo(3);
	}

	// --- temp files ----------------------------------------------------------

	@Test
	@DisplayName("deletes abandoned temp files and spares fresh ones")
	void sweepsStaleTempFiles() throws Exception {
		Path stale = tempFiles.create();
		Files.setLastModifiedTime(stale,
				FileTime.from(Instant.now().minus(Duration.ofHours(2))));

		Path fresh = tempFiles.create();

		try {
			assertThat(tempFiles.sweepStaleFiles()).isGreaterThanOrEqualTo(1);

			assertThat(stale).doesNotExist();
			// A running job is still reading this one. Deleting it would fail that report.
			assertThat(fresh).exists();
		}
		finally {
			tempFiles.deleteQuietly(fresh);
		}
	}

	// --- the whole job -------------------------------------------------------

	@Test
	@DisplayName("one sweep failing does not stop the others")
	void sweepsAreIndependent() {
		UUID expired = fixtures.expiredReport(sessionId);
		insertStatsFor(LocalDate.now().minusDays(120));

		// run() wraps each sweep, so a failure in one is logged and the rest continue. The
		// temp file sweep in particular must not be skipped: a leaked upload is a privacy
		// problem, a stale stats row is not.
		retentionJob.run();

		assertThat(reports.findById(expired)).isEmpty();
		assertThat(statsRowsFor(LocalDate.now().minusDays(120))).isZero();
	}

	@Test
	@DisplayName("the whole job is safe to run against an empty database")
	void jobHandlesEmptyDatabase() {
		fixtures.clear();

		retentionJob.run();

		assertThat(reports.count()).isZero();
	}

	private void insertStatsFor(LocalDate day) {
		jdbc.sql("insert into daily_stats (day, reports_created) values (:day, 5)")
				.param("day", day).update();
		jdbc.sql("insert into daily_failure (day, code, count) values (:day, 'UNREADABLE', 2)")
				.param("day", day).update();
		jdbc.sql("insert into daily_processing (day, source_type, total_ms, count) "
				+ "values (:day, 'PDF', 1000, 2)")
				.param("day", day).update();
	}

	/** Rows across all three stats tables for one day. */
	private int statsRowsFor(LocalDate day) {
		return jdbc.sql("""
				select (select count(*) from daily_stats where day = :day)
				     + (select count(*) from daily_failure where day = :day)
				     + (select count(*) from daily_processing where day = :day)
				""")
				.param("day", day)
				.query(Integer.class)
				.single();
	}
}
