package dev.saq.mediscan.support;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.session.OwnerRef;
import dev.saq.mediscan.session.Session;
import dev.saq.mediscan.session.SessionRepository;
import dev.saq.mediscan.session.SessionService;
import dev.saq.mediscan.user.UserRepository;
import dev.saq.mediscan.user.UserRole;
import dev.saq.mediscan.user.UserUpsert;

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
	private final UserUpsert users;
	private final UserRepository userRepository;
	private final Clock clock;

	public ReportFixtures(SessionRepository sessions, ReportRepository reports,
			SessionService sessionService, UserUpsert users, UserRepository userRepository,
			Clock clock) {
		this.sessions = sessions;
		this.reports = reports;
		this.sessionService = sessionService;
		this.users = users;
		this.userRepository = userRepository;
		this.clock = clock;
	}

	/**
	 * A user account, created the same way sign-in creates one.
	 *
	 * <p>Through {@link UserUpsert} rather than an insert of its own, so a test is working
	 * against the row shape production actually writes - including the role.
	 *
	 * @param googleSub must be unique per test; the column is unique and a collision would
	 *     silently return the other test's user
	 */
	public UUID user(String googleSub, UserRole role) {
		return users.upsert(googleSub, "Test User", role, Instant.now(clock)).userId();
	}

	/** A {@code USER}-role account with a unique Google subject. */
	public UUID user() {
		return user("test-sub-" + UUID.randomUUID(), UserRole.USER);
	}

	/** An {@code ADMIN}-role account with a unique Google subject. */
	public UUID adminUser() {
		return user("test-admin-" + UUID.randomUUID(), UserRole.ADMIN);
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
		reports.save(new ReportEntity(id, new OwnerRef.Guest(sessionId), sourceType, now,
				now.plus(24, ChronoUnit.HOURS)));
		return id;
	}

	/** A {@code PENDING} report owned by a signed-in user, with the 30-day user expiry. */
	public UUID pendingUserReport(UUID userId) {
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		reports.save(new ReportEntity(id, new OwnerRef.User(userId), SourceType.SAMPLE, now,
				now.plus(30, ChronoUnit.DAYS)));
		return id;
	}

	/** A report that has already expired, for retention and visibility tests. */
	public UUID expiredReport(UUID sessionId) {
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		reports.save(new ReportEntity(id, new OwnerRef.Guest(sessionId), SourceType.SAMPLE,
				now.minus(48, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS)));
		return id;
	}

	/**
	 * Deletes every session and every user, and with them every report, via the cascades.
	 *
	 * <p>Both owner kinds, necessarily: a report now cascades from {@code session} <em>or</em>
	 * from {@code app_user}, so clearing only sessions would leave every user-owned report
	 * behind - and the suite shares one database across test classes.
	 */
	public void clear() {
		sessions.deleteAll();
		userRepository.deleteAll();
	}

	/** 64 hex characters, unique per session, matching the column's length. */
	private static String uniqueHash(UUID id) {
		return id.toString().replace("-", "") + "0".repeat(32);
	}
}
