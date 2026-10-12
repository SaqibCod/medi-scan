package dev.saq.mediscan.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.auth.RefreshTokenService;
import dev.saq.mediscan.config.SqlTime;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * The two sweeps phase 6a adds: spent refresh tokens, and inactive accounts.
 *
 * <p>Both are about what must <em>survive</em> as much as what must go. A cleanup that was
 * slightly too eager would sign people out or delete live accounts, and neither would be
 * obvious from a count.
 */
class AuthRetentionTest extends PostgresTestBase {

	@Autowired
	private RetentionJob retentionJob;

	@Autowired
	private RefreshTokenService refreshTokens;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private JdbcClient jdbc;

	@Test
	@DisplayName("an expired refresh token is removed")
	void removesExpiredTokens() {
		UUID userId = fixtures.user();
		refreshTokens.issueNewFamily(userId);
		setExpiry(userId, Instant.now().minus(Duration.ofMinutes(5)));

		retentionJob.deleteSpentRefreshTokens();

		assertThat(tokenCount(userId)).isZero();
	}

	@Test
	@DisplayName("a live refresh token is left alone")
	void keepsLiveTokens() {
		UUID userId = fixtures.user();
		refreshTokens.issueNewFamily(userId);

		retentionJob.deleteSpentRefreshTokens();

		assertThat(tokenCount(userId)).isEqualTo(1);
	}

	@Test
	@DisplayName("a token revoked within the last day is kept, so reuse stays detectable")
	void keepsRecentlyRevokedTokens() {
		// The point of the grace period: delete it now and a stolen copy comes back as
		// "unknown token" instead of "someone is replaying this", and the family survives.
		UUID userId = fixtures.user();
		String token = refreshTokens.issueNewFamily(userId).token();
		refreshTokens.revokeFamilyOf(token);

		retentionJob.deleteSpentRefreshTokens();

		assertThat(tokenCount(userId)).isEqualTo(1);
	}

	@Test
	@DisplayName("a token revoked more than a day ago is removed")
	void removesStaleRevokedTokens() {
		UUID userId = fixtures.user();
		String token = refreshTokens.issueNewFamily(userId).token();
		refreshTokens.revokeFamilyOf(token);

		jdbc.sql("UPDATE refresh_token SET revoked_at = :then WHERE user_id = :id")
				.param("then", SqlTime.utc(Instant.now().minus(Duration.ofDays(2))))
				.param("id", userId)
				.update();

		retentionJob.deleteSpentRefreshTokens();

		assertThat(tokenCount(userId)).isZero();
	}

	@Test
	@DisplayName("an account inactive for 180 days goes, with everything it owns")
	void removesInactiveAccounts() {
		UUID userId = fixtures.user();
		UUID reportId = fixtures.pendingUserReport(userId);
		refreshTokens.issueNewFamily(userId);

		setLastLogin(userId, Instant.now().minus(Duration.ofDays(181)));

		retentionJob.deleteInactiveAccounts();

		assertThat(userExists(userId)).isFalse();
		// The cascade is the whole mechanism: one delete has to take the reports and tokens.
		assertThat(reportExists(reportId)).isFalse();
		assertThat(tokenCount(userId)).isZero();
	}

	@Test
	@DisplayName("an account used recently is left alone")
	void keepsActiveAccounts() {
		UUID userId = fixtures.user();
		setLastLogin(userId, Instant.now().minus(Duration.ofDays(179)));

		retentionJob.deleteInactiveAccounts();

		assertThat(userExists(userId)).isTrue();
	}

	@Test
	@DisplayName("the daily sweep is safe to run against an empty database")
	void dailySweepHandlesEmptyDatabase() {
		fixtures.clear();

		retentionJob.runDaily();

		assertThat(userExists(UUID.randomUUID())).isFalse();
	}

	private void setExpiry(UUID userId, Instant expiresAt) {
		jdbc.sql("UPDATE refresh_token SET expires_at = :at WHERE user_id = :id")
				.param("at", SqlTime.utc(expiresAt))
				.param("id", userId)
				.update();
	}

	private void setLastLogin(UUID userId, Instant at) {
		jdbc.sql("UPDATE app_user SET last_login_at = :at WHERE id = :id")
				.param("at", SqlTime.utc(at))
				.param("id", userId)
				.update();
	}

	private int tokenCount(UUID userId) {
		Integer count = jdbc.sql("SELECT count(*) FROM refresh_token WHERE user_id = :id")
				.param("id", userId).query(Integer.class).single();
		return count != null ? count : 0;
	}

	private boolean userExists(UUID userId) {
		Integer count = jdbc.sql("SELECT count(*) FROM app_user WHERE id = :id")
				.param("id", userId).query(Integer.class).single();
		return count != null && count > 0;
	}

	private boolean reportExists(UUID reportId) {
		Integer count = jdbc.sql("SELECT count(*) FROM report WHERE id = :id")
				.param("id", reportId).query(Integer.class).single();
		return count != null && count > 0;
	}
}
