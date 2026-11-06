package dev.saq.mediscan.retention;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.session.SessionRepository;

/**
 * Deletes expired data on a schedule ({@code docs/dataflow.md} section 9.2).
 *
 * <p>Phase 1 covers the guest half: expired sessions, which cascade to the reports they own.
 * Expired user reports, old refresh tokens, stale temp files, inactive accounts and stats
 * older than 90 days are added by the phases that create them.
 *
 * <p>This job is a backstop, not the only line of defence. Every read also filters on
 * {@code expires_at > now()}, so expired data is never served even in the window between a
 * session expiring and this job running.
 */
@Component
public class RetentionJob {

	private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

	private final SessionRepository sessionRepository;

	public RetentionJob(SessionRepository sessionRepository) {
		this.sessionRepository = sessionRepository;
	}

	/**
	 * Every 15 minutes, with a short initial delay so startup is not competing with a
	 * database write.
	 *
	 * <p>{@code fixedDelayString} rather than {@code fixedRateString}: a slow run should push
	 * the next one back, not have two overlapping deletes.
	 */
	@Scheduled(fixedDelayString = "${mediscan.retention.interval-ms}", initialDelay = 60_000)
	@Transactional
	public void deleteExpiredSessions() {
		Instant now = Instant.now();
		int deleted = sessionRepository.deleteExpired(now);

		// Counts only, never ids or content.
		if (deleted > 0) {
			log.info("Retention: deleted {} expired guest sessions", deleted);
		}
		else {
			log.debug("Retention: no expired guest sessions");
		}
	}
}
