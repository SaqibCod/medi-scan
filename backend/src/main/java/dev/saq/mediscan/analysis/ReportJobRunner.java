package dev.saq.mediscan.analysis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.report.ReportResultSaver;
import dev.saq.mediscan.report.ReportStatusService;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.upload.TempFileStore;

/**
 * Wraps {@link ReportJob} with everything that must happen whatever it does.
 *
 * <p>Four responsibilities, all of them about the cases where the pipeline does not simply
 * work:
 *
 * <ul>
 * <li><strong>The temp file always goes.</strong> In a {@code finally}, so an unmasked medical
 * report is never left on disk - not on failure, not on an unexpected exception
 * ({@code CLAUDE.md} rule 2).</li>
 * <li><strong>Every failure becomes a status.</strong> A report that stops silently would be
 * polled by the client until it expired.</li>
 * <li><strong>Unexpected exceptions are logged by class only.</strong> Never the message: an
 * exception from a parser or a provider can quote report text, and that is the one thing that
 * must not reach a log line ({@code backend/CLAUDE.md}, "Exception logging").</li>
 * <li><strong>The report id is in the MDC</strong> for the whole run, so every line a job
 * emits can be tied to its report ({@code LLD} 16).</li>
 * </ul>
 *
 * <p>This is also the {@link ReportJobFactory}, so the upload layer's one call into
 * {@code analysis} stays a single method.
 */
@Component
public class ReportJobRunner implements ReportJobFactory {

	private static final Logger log = LoggerFactory.getLogger(ReportJobRunner.class);

	/** MDC key, alongside the request id the Phase 1 filter sets. */
	private static final String MDC_REPORT_ID = "reportId";

	private final ReportJob job;
	private final ReportStatusService statuses;
	private final TempFileStore tempFiles;
	private final JobObserver observer;
	private final Clock clock;

	/**
	 * @param observer optional, so a finished report is never lost to a stats failure
	 */
	public ReportJobRunner(ReportJob job, ReportStatusService statuses, TempFileStore tempFiles,
			ObjectProvider<JobObserver> observer, Clock clock) {

		this.job = job;
		this.statuses = statuses;
		this.tempFiles = tempFiles;
		this.observer = observer.getIfAvailable(NoOpJobObserver::new);
		this.clock = clock;
	}

	@Override
	public Runnable create(UUID reportId, JobSource source) {
		return () -> run(reportId, source);
	}

	private void run(UUID reportId, JobSource source) {
		MDC.put(MDC_REPORT_ID, reportId.toString());
		Instant startedAt = Instant.now(clock);
		ReportErrorCode failureCode = null;
		ReportJob.Outcome outcome = ReportJob.Outcome.SKIPPED;

		try {
			outcome = job.run(reportId, source);
		}
		catch (ReportFailure failure) {
			// An expected failure. The code is the whole payload - ReportFailure carries no
			// message precisely so there is nothing content-bearing to log.
			failureCode = failure.code();
			statuses.markFailed(reportId, failureCode);
			log.info("report failed id={} code={}", reportId, failureCode);
		}
		catch (ReportResultSaver.ReportVanishedException ex) {
			// Deleted mid-save. Not a failure: the caller asked for it to be gone.
			log.info("report deleted during processing id={}", reportId);
		}
		catch (Throwable unexpected) {
			// A bug, or something genuinely unforeseen. The report still has to reach a
			// terminal status, and the exception class is all that gets logged.
			failureCode = ReportErrorCode.EXTRACTION_FAILED;
			statuses.markFailed(reportId, failureCode);
			log.error("report job threw id={} exception={}", reportId,
					unexpected.getClass().getName());
		}
		finally {
			// Before the stats, before the MDC clear, and whatever happened above.
			tempFiles.deleteQuietly(source);

			long durationMs = Duration.between(startedAt, Instant.now(clock)).toMillis();
			recordOutcome(reportId, source, outcome, failureCode, durationMs);

			MDC.remove(MDC_REPORT_ID);
		}
	}

	private void recordOutcome(UUID reportId, JobSource source, ReportJob.Outcome outcome,
			ReportErrorCode failureCode, long durationMs) {

		try {
			if (failureCode != null) {
				observer.jobFailed(source.sourceType(), failureCode, durationMs);
			}
			else if (outcome == ReportJob.Outcome.DONE) {
				log.info("report done id={} ms={}", reportId, durationMs);
				observer.jobCompleted(source.sourceType(), durationMs);
			}
		}
		catch (RuntimeException ex) {
			// Stats are not worth failing a finished report over.
			log.warn("could not record job stats: {}", ex.getClass().getSimpleName());
		}
	}

	/**
	 * Where job outcomes go.
	 *
	 * <p>An interface so {@code analysis} does not depend on {@code stats}, and so a test can
	 * assert what was recorded without a database.
	 */
	public interface JobObserver {

		void jobCompleted(SourceType sourceType, long durationMs);

		void jobFailed(SourceType sourceType, ReportErrorCode code, long durationMs);
	}

	/** Used when nothing is recording, so the job needs no null check. */
	private static final class NoOpJobObserver implements JobObserver {

		@Override
		public void jobCompleted(SourceType sourceType, long durationMs) {
		}

		@Override
		public void jobFailed(SourceType sourceType, ReportErrorCode code, long durationMs) {
		}
	}
}
