package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.ReportStatus;
import dev.saq.mediscan.report.ReportStatusService;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * Restart recovery ({@code LLD} 7.3).
 *
 * <p>A job cannot be resumed: its temp file went with the restart and its extracted text only
 * ever existed in memory. Without this, an interrupted report would sit at {@code PENDING} for
 * ever and the client would poll it until it expired - so {@code INTERRUPTED} with a prompt to
 * upload again is the honest outcome.
 *
 * <p>Invoked directly rather than by restarting the context, because the behaviour under test
 * is the update, not the Spring event wiring.
 */
class StartupRecoveryTest extends PostgresTestBase {

	@Autowired
	StartupRecovery recovery;

	@Autowired
	ReportStatusService statuses;

	@Autowired
	ReportRepository reports;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	JdbcClient jdbc;

	private UUID sessionId;

	@BeforeEach
	void reset() {
		fixtures.clear();
		jdbc.sql("delete from daily_failure").update();
		sessionId = fixtures.session();
	}

	@Test
	@DisplayName("fails a report left PENDING by a restart")
	void failsPendingReport() {
		UUID reportId = fixtures.pendingReport(sessionId);

		recovery.failInterruptedReports();

		assertThat(reports.findById(reportId)).isPresent().get().satisfies(report -> {
			assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
			assertThat(report.getErrorCode()).isEqualTo(ReportErrorCode.INTERRUPTED);
			assertThat(report.getFinishedAt()).isNotNull();
		});
	}

	@Test
	@DisplayName("fails a report left PROCESSING by a restart")
	void failsProcessingReport() {
		UUID reportId = fixtures.pendingReport(sessionId);
		statuses.markProcessing(reportId);

		recovery.failInterruptedReports();

		assertThat(reports.findById(reportId)).isPresent().get()
				.satisfies(report -> assertThat(report.getErrorCode())
						.isEqualTo(ReportErrorCode.INTERRUPTED));
	}

	@Test
	@DisplayName("leaves a finished report's outcome intact")
	void leavesTerminalReportsAlone() {
		UUID done = fixtures.pendingReport(sessionId);
		statuses.markProcessing(done);
		statuses.markDone(done, null);

		UUID failed = fixtures.pendingReport(sessionId);
		statuses.markProcessing(failed);
		statuses.markFailed(failed, ReportErrorCode.NO_RESULTS_FOUND);

		recovery.failInterruptedReports();

		// A report that finished before the restart must survive it unchanged. Overwriting a
		// DONE report would lose results the user had already been shown.
		assertThat(reports.findById(done).orElseThrow().getStatus())
				.isEqualTo(ReportStatus.DONE);
		assertThat(reports.findById(failed).orElseThrow().getErrorCode())
				.isEqualTo(ReportErrorCode.NO_RESULTS_FOUND);
	}

	@Test
	@DisplayName("counts the interrupted reports so a restart is visible on the dashboard")
	void countsInterruptedReports() {
		fixtures.pendingReport(sessionId);
		fixtures.pendingReport(sessionId);

		recovery.failInterruptedReports();

		// Without this, a restart would show up as a gap in the failure codes rather than as
		// a restart.
		assertThat(interruptedCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("does nothing, and counts nothing, when there is nothing to recover")
	void doesNothingWhenClean() {
		recovery.failInterruptedReports();

		assertThat(interruptedCount()).isZero();
	}

	@Test
	@DisplayName("is idempotent, so a crash loop does not inflate the count")
	void isIdempotent() {
		fixtures.pendingReport(sessionId);

		recovery.failInterruptedReports();
		recovery.failInterruptedReports();

		assertThat(interruptedCount()).isEqualTo(1);
	}

	private int interruptedCount() {
		return jdbc.sql("select coalesce(sum(count), 0) from daily_failure "
				+ "where code = 'INTERRUPTED'")
				.query(Integer.class)
				.single();
	}
}
