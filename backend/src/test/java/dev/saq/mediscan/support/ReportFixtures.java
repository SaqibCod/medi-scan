package dev.saq.mediscan.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.session.Session;
import dev.saq.mediscan.session.SessionRepository;
import dev.saq.mediscan.session.SessionService;

/**
 * Sessions and reports for tests that need rows to exist.
 *
 * <p>A bean rather than static helpers so it can use the real repositories: tests assert
 * against the same persistence path production uses, instead of a second way of writing rows
 * that could drift from it.
 */
@Component
public class ReportFixtures {

	private final SessionRepository sessions;
	private final ReportRepository reports;
	private final SessionService sessionService;

	public ReportFixtures(SessionRepository sessions, ReportRepository reports,
			SessionService sessionService) {
		this.sessions = sessions;
		this.reports = reports;
		this.sessionService = sessionService;
	}

	/**
	 * A live session's raw token, for tests that call an endpoint.
	 *
	 * <p>Goes through {@link SessionService} rather than inserting a row and inventing a
	 * token, because the token the client sends has to hash to the stored value - so building
	 * one by hand would mean reimplementing the hashing the filter depends on.
	 */
	public String sessionToken() {
		return sessionService.create().token();
	}

	/** The session id behind a raw token, for tests that need to query by owner. */
	public UUID sessionIdFor(String token) {
		return sessionService.findActive(token).orElseThrow().getId();
	}

	/** A live guest session, expiring in 24 hours. */
	public UUID session() {
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		sessions.save(new Session(id, uniqueHash(id), now, now.plus(24, ChronoUnit.HOURS)));
		return id;
	}

	/** A {@code PENDING} report owned by {@code sessionId}, expiring in 24 hours. */
	public UUID pendingReport(UUID sessionId) {
		return pendingReport(sessionId, SourceType.SAMPLE);
	}

	public UUID pendingReport(UUID sessionId, SourceType sourceType) {
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		reports.save(new ReportEntity(id, sessionId, sourceType, now, now.plus(24, ChronoUnit.HOURS)));
		return id;
	}

	/** A report that has already expired, for retention and visibility tests. */
	public UUID expiredReport(UUID sessionId) {
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		reports.save(new ReportEntity(id, sessionId, SourceType.SAMPLE,
				now.minus(48, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS)));
		return id;
	}

	/** Deletes every session, and with it every report, via the cascade. */
	public void clear() {
		sessions.deleteAll();
	}

	/** 64 hex characters, unique per session, matching the column's length. */
	private static String uniqueHash(UUID id) {
		return id.toString().replace("-", "") + "0".repeat(32);
	}
}
