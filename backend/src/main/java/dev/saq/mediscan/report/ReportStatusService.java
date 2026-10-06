package dev.saq.mediscan.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.saq.mediscan.config.SqlTime;

/**
 * Moves a report between statuses, always as a conditional update.
 *
 * <p>Every statement here carries its expected current status in the {@code WHERE} clause and
 * the caller checks the affected row count. That is what makes the pipeline safe against two
 * things that otherwise need locks:
 *
 * <ul>
 * <li><strong>A deleted report.</strong> {@code DELETE /api/reports/{id}} can land at any
 * moment. The next transition then matches no row, the job sees {@code false} and stops, and
 * nothing is written for an id the caller already removed ({@code LLD} 7.4).</li>
 * <li><strong>Two writers.</strong> Only one can win a {@code PENDING -> PROCESSING} update,
 * so a report cannot be processed twice even if it were submitted twice.</li>
 * </ul>
 *
 * <p>{@link JdbcClient} rather than JPA, per {@code backend/CLAUDE.md}: these are conditional
 * updates whose row count is the result, and an entity write would read-modify-write instead
 * of letting the database arbitrate.
 */
@Service
public class ReportStatusService {

	private final JdbcClient jdbc;
	private final Clock clock;

	public ReportStatusService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * Claims a queued report for this worker.
	 *
	 * @return {@code true} when this call won the claim; {@code false} when the report was
	 *     deleted, has expired out from under the job, or was already claimed - in all of
	 *     which cases the caller must stop without writing anything
	 */
	public boolean markProcessing(UUID reportId) {
		int updated = jdbc.sql("""
				update report
				   set status = 'PROCESSING', started_at = :now
				 where id = :id and status = 'PENDING'
				""")
				.param("id", reportId)
				.param("now", SqlTime.utc(Instant.now(clock)))
				.update();
		return updated == 1;
	}

	/**
	 * Records a failure against a report that this worker is processing.
	 *
	 * <p>Guarded on {@code PROCESSING} so a late failure cannot overwrite a {@code DONE}
	 * report, and cannot resurrect a deleted one.
	 *
	 * @return {@code true} when the report was marked failed
	 */
	public boolean markFailed(UUID reportId, ReportErrorCode code) {
		int updated = jdbc.sql("""
				update report
				   set status = 'FAILED', error_code = :code, finished_at = :now
				 where id = :id and status = 'PROCESSING'
				""")
				.param("id", reportId)
				.param("code", code.name())
				.param("now", SqlTime.utc(Instant.now(clock)))
				.update();
		return updated == 1;
	}

	/**
	 * Marks a report done. Called inside {@code ReportResultSaver}'s transaction, before the
	 * child rows are inserted, so a report deleted mid-job fails this check and the whole
	 * transaction rolls back rather than inserting orphans.
	 *
	 * @return {@code true} when the report was still {@code PROCESSING} and is now
	 *     {@code DONE}; {@code false} means the caller must roll back
	 */
	public boolean markDone(UUID reportId, LocalDate collectedOn) {
		int updated = jdbc.sql("""
				update report
				   set status = 'DONE', collected_on = :collectedOn, finished_at = :now
				 where id = :id and status = 'PROCESSING'
				""")
				.param("id", reportId)
				.param("collectedOn", collectedOn)
				.param("now", SqlTime.utc(Instant.now(clock)))
				.update();
		return updated == 1;
	}

	/**
	 * Fails every report a restart left mid-flight ({@code LLD} 7.3).
	 *
	 * <p>Unconditional on id but still conditional on status. A job cannot be resumed: its
	 * temp file went with the restart and its in-memory text is gone, so the honest outcome
	 * is {@code INTERRUPTED} and a prompt to upload again.
	 *
	 * @return how many reports were failed
	 */
	public int failInterrupted() {
		return jdbc.sql("""
				update report
				   set status = 'FAILED', error_code = 'INTERRUPTED', finished_at = :now
				 where status in ('PENDING', 'PROCESSING')
				""")
				.param("now", SqlTime.utc(Instant.now(clock)))
				.update();
	}

	/** The statuses {@link #failInterrupted()} targets, for tests and logging. */
	public static List<ReportStatus> interruptibleStatuses() {
		return List.of(ReportStatus.PENDING, ReportStatus.PROCESSING);
	}
}
