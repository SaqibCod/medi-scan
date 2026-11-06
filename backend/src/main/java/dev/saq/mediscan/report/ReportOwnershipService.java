package dev.saq.mediscan.report;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Moves a guest session's reports to a user account at sign-in.
 *
 * <p>One statement, and the order around it matters more than the statement does. The update
 * has to happen <em>before</em> the guest session row is deleted, because {@code report}
 * cascades from {@code session} - delete the session first and the cascade takes the reports
 * with it, and the user signs in to an empty history (LLD 7.1).
 *
 * <p>Reports still {@code PENDING} or {@code PROCESSING} move too. The background job writes
 * by report id and its status updates are conditional on status, not on owner, so a job in
 * flight finishes against the new owner without noticing anything happened.
 */
@Service
public class ReportOwnershipService {

	private final JdbcClient jdbc;

	public ReportOwnershipService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Hands every report owned by {@code sessionId} to {@code userId}.
	 *
	 * <p>Not transactional on its own: sign-in wraps the user upsert, this, the session
	 * delete and the refresh-token insert in one transaction, so a failure anywhere leaves
	 * the guest with their session and their reports intact.
	 *
	 * <p>Expiry is recomputed from {@code created_at}, not extended from today, so claiming a
	 * report does not quietly give an old one a fresh 30 days. {@code session_id} is set to
	 * null in the same statement because the check constraint allows exactly one owner.
	 *
	 * @return how many reports moved
	 */
	public int claimGuestReports(UUID sessionId, UUID userId, int userRetentionDays) {
		return jdbc.sql("""
				UPDATE report
				   SET user_id = :userId,
				       session_id = NULL,
				       expires_at = created_at + make_interval(days => :days)
				 WHERE session_id = :sessionId
				""")
				.param("userId", userId)
				.param("sessionId", sessionId)
				.param("days", userRetentionDays)
				.update();
	}
}
