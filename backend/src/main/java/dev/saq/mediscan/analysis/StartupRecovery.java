package dev.saq.mediscan.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.report.ReportStatusService;
import dev.saq.mediscan.stats.StatsRecorder;

/**
 * Fails reports a restart left mid-flight ({@code LLD} 7.3).
 *
 * <p>A job cannot be resumed. Its temp file went with the restart and its extracted text only
 * ever existed in memory, so there is nothing left to continue from. Without this, those
 * reports would sit at {@code PENDING} or {@code PROCESSING} for ever and the client would
 * poll them until they expired - so the honest outcome is {@code INTERRUPTED} and a prompt to
 * upload again.
 *
 * <p>Runs on {@link ApplicationReadyEvent} rather than at bean construction, so it happens
 * after Flyway and after the job executor exists - a report failed before the pool was ready
 * could not be re-submitted anyway, but the ordering keeps the log honest about when it
 * happened.
 */
@Component
public class StartupRecovery {

	private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

	private final ReportStatusService statuses;
	private final StatsRecorder stats;

	public StartupRecovery(ReportStatusService statuses, StatsRecorder stats) {
		this.statuses = statuses;
		this.stats = stats;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void failInterruptedReports() {
		int failed = statuses.failInterrupted();

		if (failed == 0) {
			log.info("Startup recovery: no interrupted reports");
			return;
		}

		// Counted so the admin dashboard shows restarts for what they are, rather than as a
		// mysterious gap in the failure codes.
		stats.recordFailureCode(ReportErrorCode.INTERRUPTED, failed);
		log.warn("Startup recovery: failed {} interrupted report(s)", failed);
	}
}
