package dev.saq.mediscan.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.session.SessionRepository;
import dev.saq.mediscan.stats.StatsRecorder;
import dev.saq.mediscan.upload.TempFileStore;

/**
 * Deletes expired data on a schedule ({@code docs/dataflow.md} section 9.2).
 *
 * <p>Four sweeps, each a backstop rather than the primary mechanism. Sessions cascade to the
 * reports they own, so that one deletion removes everything below it. Reports also carry their
 * own {@code expires_at}, swept separately because phase 6 gives a signed-in user's reports a
 * 30-day life that no session cascade covers. Temp files are deleted by the jobs that created
 * them, so the sweep only catches files orphaned by a JVM kill. Stats outlive everything else
 * and are the only thing here with a horizon of its own.
 *
 * <p>Every read in the application also filters on {@code expires_at > now()}, so expired data
 * is never served in the window between expiry and this job running. That is what makes the
 * schedule a matter of housekeeping rather than correctness.
 *
 * <p><strong>Sweeps are independent.</strong> One failing must not stop the others, because a
 * leaked temp file is a privacy problem and a stale stats row is not.
 *
 * <p><strong>Transactions come from a {@link TransactionTemplate}, not {@code @Transactional}.
 * </strong> {@code @Transactional} is applied by a proxy, and a proxy is only involved when a
 * method is called from outside the bean. {@link #run()} calls the sweeps on {@code this}, so
 * annotations on them would be silently ignored - the bulk deletes would run with no
 * transaction and fail. Declaring the boundary explicitly is both correct and visible.
 */
@Component
public class RetentionJob {

	private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

	private final SessionRepository sessionRepository;
	private final ReportRepository reportRepository;
	private final TempFileStore tempFiles;
	private final StatsRecorder stats;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final int statsDays;

	public RetentionJob(SessionRepository sessionRepository, ReportRepository reportRepository,
			TempFileStore tempFiles, StatsRecorder stats,
			PlatformTransactionManager transactionManager, Clock clock,
			MediScanProperties properties) {

		this.sessionRepository = sessionRepository;
		this.reportRepository = reportRepository;
		this.tempFiles = tempFiles;
		this.stats = stats;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.statsDays = properties.retention().statsDays();
	}

	/**
	 * Every 15 minutes, with a short initial delay so startup is not competing with a
	 * database write.
	 *
	 * <p>{@code fixedDelayString} rather than {@code fixedRateString}: a slow run should push
	 * the next one back, not have two overlapping deletes.
	 */
	@Scheduled(fixedDelayString = "${mediscan.retention.interval-ms}", initialDelay = 60_000)
	public void run() {
		runSweep("expired sessions", this::deleteExpiredSessions);
		runSweep("expired reports", this::deleteExpiredReports);
		runSweep("stale temp files", tempFiles::sweepStaleFiles);
		runSweep("old stats", this::deleteOldStats);
	}

	/**
	 * Expired guest sessions, which cascade to their reports.
	 *
	 * @return how many sessions were removed
	 */
	public int deleteExpiredSessions() {
		int deleted = inTransaction(() -> sessionRepository.deleteExpired(Instant.now(clock)));
		if (deleted > 0) {
			// Counts only, never ids or content.
			log.info("Retention: deleted {} expired guest sessions", deleted);
		}
		return deleted;
	}

	/**
	 * Expired reports, as a safety net.
	 *
	 * <p>Normally redundant in phase 2: a guest's report expires with its session and goes via
	 * the cascade. It exists because {@code report.expires_at} is its own column, and phase 6
	 * adds user-owned reports with a 30-day life and no session to cascade from.
	 *
	 * @return how many reports were removed
	 */
	public int deleteExpiredReports() {
		int deleted = inTransaction(() -> reportRepository.deleteExpired(Instant.now(clock)));
		if (deleted > 0) {
			log.info("Retention: deleted {} expired reports", deleted);
		}
		return deleted;
	}

	/**
	 * Aggregated counters past the retention horizon.
	 *
	 * @return how many rows were removed
	 */
	public int deleteOldStats() {
		LocalDate cutoff = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(statsDays);
		return inTransaction(() -> stats.deleteOlderThan(cutoff));
	}

	private int inTransaction(Sweep sweep) {
		Integer deleted = transactions.execute(status -> sweep.run());
		return deleted != null ? deleted : 0;
	}

	/**
	 * Runs one sweep, logging rather than propagating a failure.
	 *
	 * <p>A scheduled method that throws is simply not retried until the next tick, and one
	 * failing sweep must not prevent the others - particularly not the temp file sweep, which
	 * is the one with a privacy consequence.
	 */
	private void runSweep(String name, Sweep sweep) {
		try {
			sweep.run();
		}
		catch (RuntimeException ex) {
			log.error("Retention sweep '{}' failed: {}", name, ex.getClass().getSimpleName());
		}
	}

	@FunctionalInterface
	private interface Sweep {

		int run();
	}
}
