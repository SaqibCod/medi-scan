package dev.saq.mediscan.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * The conditional status transitions ({@code LLD} 7.1).
 *
 * <p>These tests exist for the races, not the happy path. Every transition is guarded on the
 * status it expects, and the whole pipeline's safety against a mid-job delete and against
 * double submission rests on those guards returning {@code false} rather than throwing or
 * silently writing.
 */
class ReportStatusServiceTest extends PostgresTestBase {

	@Autowired
	ReportStatusService statuses;

	@Autowired
	ReportRepository reports;

	@Autowired
	ReportFixtures fixtures;

	private UUID sessionId;

	@BeforeEach
	void freshSession() {
		fixtures.clear();
		sessionId = fixtures.session();
	}

	@Test
	@DisplayName("claims a PENDING report and records started_at")
	void claimsPending() {
		UUID reportId = fixtures.pendingReport(sessionId);

		assertThat(statuses.markProcessing(reportId)).isTrue();

		ReportEntity report = load(reportId);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.PROCESSING);
		assertThat(report.getStartedAt()).isNotNull();
		assertThat(report.getFinishedAt()).isNull();
	}

	@Test
	@DisplayName("only one of many concurrent claims wins")
	void claimIsExclusive() throws Exception {
		UUID reportId = fixtures.pendingReport(sessionId);

		// If the guard were a read-then-write, several of these would claim the same report
		// and the pipeline would run it more than once, spending the LLM budget twice.
		List<Callable<Boolean>> claims = IntStream.range(0, 8)
				.mapToObj(i -> (Callable<Boolean>) () -> statuses.markProcessing(reportId))
				.toList();

		try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
			long winners = pool.invokeAll(claims).stream()
					.map(future -> {
						try {
							return future.get();
						}
						catch (Exception ex) {
							throw new IllegalStateException(ex);
						}
					})
					.filter(Boolean::booleanValue)
					.count();

			assertThat(winners).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("a report that is gone cannot be claimed")
	void deletedReportIsNotClaimed() {
		UUID reportId = fixtures.pendingReport(sessionId);
		reports.deleteById(reportId);

		assertThat(statuses.markProcessing(reportId)).isFalse();
	}

	@Test
	@DisplayName("failing requires the report to be PROCESSING")
	void failOnlyFromProcessing() {
		UUID reportId = fixtures.pendingReport(sessionId);

		// Still PENDING: a failure here would mean a worker reported on a report it never
		// claimed.
		assertThat(statuses.markFailed(reportId, ReportErrorCode.UNREADABLE)).isFalse();

		statuses.markProcessing(reportId);
		assertThat(statuses.markFailed(reportId, ReportErrorCode.UNREADABLE)).isTrue();

		ReportEntity report = load(reportId);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
		assertThat(report.getErrorCode()).isEqualTo(ReportErrorCode.UNREADABLE);
		assertThat(report.getFinishedAt()).isNotNull();
	}

	@Test
	@DisplayName("a late failure cannot overwrite a DONE report")
	void failureCannotOverwriteDone() {
		UUID reportId = fixtures.pendingReport(sessionId);
		statuses.markProcessing(reportId);
		statuses.markDone(reportId, LocalDate.of(2026, 9, 28));

		assertThat(statuses.markFailed(reportId, ReportErrorCode.LLM_UNAVAILABLE)).isFalse();

		ReportEntity report = load(reportId);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.DONE);
		assertThat(report.getErrorCode()).isNull();
	}

	@Test
	@DisplayName("marking done stores collectedOn and requires PROCESSING")
	void doneOnlyFromProcessing() {
		UUID reportId = fixtures.pendingReport(sessionId);

		assertThat(statuses.markDone(reportId, null)).isFalse();

		statuses.markProcessing(reportId);
		assertThat(statuses.markDone(reportId, LocalDate.of(2026, 9, 28))).isTrue();

		ReportEntity report = load(reportId);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.DONE);
		assertThat(report.getCollectedOn()).isEqualTo(LocalDate.of(2026, 9, 28));
		assertThat(report.getFinishedAt()).isNotNull();
	}

	@Test
	@DisplayName("a null collectedOn is stored as null, not rejected")
	void doneAcceptsNoCollectionDate() {
		UUID reportId = fixtures.pendingReport(sessionId);
		statuses.markProcessing(reportId);

		assertThat(statuses.markDone(reportId, null)).isTrue();
		assertThat(load(reportId).getCollectedOn()).isNull();
	}

	@Test
	@DisplayName("startup recovery fails PENDING and PROCESSING, and leaves terminal reports alone")
	void recoveryTargetsOnlyLiveReports() {
		UUID pending = fixtures.pendingReport(sessionId);

		UUID processing = fixtures.pendingReport(sessionId);
		statuses.markProcessing(processing);

		UUID done = fixtures.pendingReport(sessionId);
		statuses.markProcessing(done);
		statuses.markDone(done, null);

		UUID failed = fixtures.pendingReport(sessionId);
		statuses.markProcessing(failed);
		statuses.markFailed(failed, ReportErrorCode.NO_RESULTS_FOUND);

		assertThat(statuses.failInterrupted()).isEqualTo(2);

		assertThat(load(pending).getErrorCode()).isEqualTo(ReportErrorCode.INTERRUPTED);
		assertThat(load(processing).getErrorCode()).isEqualTo(ReportErrorCode.INTERRUPTED);

		// A finished report must survive a restart with its outcome intact.
		assertThat(load(done).getStatus()).isEqualTo(ReportStatus.DONE);
		assertThat(load(failed).getErrorCode()).isEqualTo(ReportErrorCode.NO_RESULTS_FOUND);
	}

	private ReportEntity load(UUID reportId) {
		return reports.findOwnedBySession(reportId, sessionId, java.time.Instant.now()).orElseThrow();
	}
}
