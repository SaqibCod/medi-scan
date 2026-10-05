package dev.saq.mediscan.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.saq.mediscan.session.Session;
import dev.saq.mediscan.session.SessionRepository;
import dev.saq.mediscan.support.PostgresTestBase;

/**
 * The guest half of retention cleanup ({@code docs/dataflow.md} section 9.2).
 *
 * <p>The job is invoked directly rather than waited on: a test that sleeps for the schedule
 * would be slow and flaky, and the schedule itself is configuration, not behaviour.
 */
class RetentionJobTest extends PostgresTestBase {

	@Autowired
	RetentionJob retentionJob;

	@Autowired
	SessionRepository sessionRepository;

	@BeforeEach
	void clearSessions() {
		// Other tests in the suite leave sessions behind, and this test counts rows.
		sessionRepository.deleteAll();
	}

	@Test
	@DisplayName("deletes expired sessions and leaves live ones alone")
	void deletesOnlyExpired() {
		Instant now = Instant.now();

		UUID expired = saveSession(now.minus(48, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));
		UUID justExpired = saveSession(now.minus(25, ChronoUnit.HOURS), now.minus(1, ChronoUnit.MINUTES));
		UUID live = saveSession(now, now.plus(23, ChronoUnit.HOURS));
		UUID justIssued = saveSession(now, now.plus(24, ChronoUnit.HOURS));

		retentionJob.deleteExpiredSessions();

		assertThat(sessionRepository.findById(expired)).isEmpty();
		assertThat(sessionRepository.findById(justExpired)).isEmpty();

		// The important half of the assertion: a cleanup job that deletes too much is worse
		// than one that deletes too little, because a live guest would lose their report.
		assertThat(sessionRepository.findById(live)).isPresent();
		assertThat(sessionRepository.findById(justIssued)).isPresent();
	}

	@Test
	@DisplayName("is safe to run when nothing has expired")
	void noopWhenNothingExpired() {
		Instant now = Instant.now();
		UUID live = saveSession(now, now.plus(12, ChronoUnit.HOURS));

		retentionJob.deleteExpiredSessions();

		assertThat(sessionRepository.findById(live)).isPresent();
		assertThat(sessionRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("is idempotent")
	void repeatedRunsAreSafe() {
		Instant now = Instant.now();
		saveSession(now.minus(48, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));
		UUID live = saveSession(now, now.plus(12, ChronoUnit.HOURS));

		retentionJob.deleteExpiredSessions();
		retentionJob.deleteExpiredSessions();

		assertThat(sessionRepository.count()).isEqualTo(1);
		assertThat(sessionRepository.findById(live)).isPresent();
	}

	private UUID saveSession(Instant createdAt, Instant expiresAt) {
		UUID id = UUID.randomUUID();
		// A unique hash per row; the value is irrelevant because nothing authenticates here.
		sessionRepository.save(new Session(id, id.toString().replace("-", "") + "0".repeat(32),
				createdAt, expiresAt));
		return id;
	}
}
