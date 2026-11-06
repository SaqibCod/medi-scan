package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.config.SqlTime;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.user.UserRole;

/**
 * Rotation, reuse detection, family limits and logout.
 *
 * <p>Against a real database because every rule here is enforced by SQL: the
 * {@code FOR UPDATE} lock, the {@code revoked_at IS NULL} conditions, and the family-wide
 * update. Mocking the repository would test the arrangement of the calls and none of the
 * behaviour that matters.
 */
class RefreshTokenServiceTest extends PostgresTestBase {

	@Autowired
	private RefreshTokenService refreshTokens;

	@Autowired
	private RefreshTokenRepository repository;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private JdbcClient jdbc;

	@Test
	@DisplayName("a fresh sign-in gets a usable token")
	void issuesUsableToken() {
		UUID userId = fixtures.user();

		RefreshTokenService.IssuedRefreshToken issued = refreshTokens.issueNewFamily(userId);

		assertThat(issued.token()).isNotBlank().hasSize(43);
		assertThat(issued.expiresAt()).isAfter(Instant.now());
		assertThat(refreshTokens.rotate(issued.token())).isInstanceOf(RefreshOutcome.Rotated.class);
	}

	@Test
	@DisplayName("the raw token is never stored, only its hash")
	void storesOnlyHashes() {
		UUID userId = fixtures.user();
		RefreshTokenService.IssuedRefreshToken issued = refreshTokens.issueNewFamily(userId);

		List<String> hashes = jdbc.sql("SELECT token_hash FROM refresh_token WHERE user_id = :id")
				.param("id", userId)
				.query(String.class)
				.list();

		assertThat(hashes).hasSize(1);
		assertThat(hashes.get(0)).isNotEqualTo(issued.token()).hasSize(64);
	}

	@Test
	@DisplayName("rotation invalidates the token that was used")
	void rotationInvalidatesTheOldToken() {
		UUID userId = fixtures.user();
		String first = refreshTokens.issueNewFamily(userId).token();

		RefreshOutcome rotated = refreshTokens.rotate(first);
		assertThat(rotated).isInstanceOf(RefreshOutcome.Rotated.class);

		// The second use of `first` is reuse, not merely "invalid": the token was real and
		// has already been exchanged.
		assertThat(refreshTokens.rotate(first)).isInstanceOf(RefreshOutcome.ReuseDetected.class);
	}

	@Test
	@DisplayName("reuse revokes the whole family, including the newest token")
	void reuseRevokesTheFamily() {
		UUID userId = fixtures.user();
		String first = refreshTokens.issueNewFamily(userId).token();
		String second = ((RefreshOutcome.Rotated) refreshTokens.rotate(first)).refreshToken();

		// Replaying the first token must not leave the thief's successor working either.
		refreshTokens.rotate(first);

		assertThat(refreshTokens.rotate(second)).isInstanceOf(RefreshOutcome.ReuseDetected.class);
		assertThat(repository.countLiveInFamily(familyOf(userId))).isZero();
	}

	@Test
	@DisplayName("an unknown token is invalid, with no family to revoke")
	void unknownTokenIsInvalid() {
		assertThat(refreshTokens.rotate("not-a-real-token"))
				.isInstanceOf(RefreshOutcome.Invalid.class);
	}

	@Test
	@DisplayName("a null or blank token is invalid rather than an error")
	void blankTokenIsInvalid() {
		assertThat(refreshTokens.rotate(null)).isInstanceOf(RefreshOutcome.Invalid.class);
		assertThat(refreshTokens.rotate("  ")).isInstanceOf(RefreshOutcome.Invalid.class);
	}

	@Test
	@DisplayName("an expired token is invalid")
	void expiredTokenIsInvalid() {
		UUID userId = fixtures.user();
		String token = refreshTokens.issueNewFamily(userId).token();

		expireToken(userId);

		assertThat(refreshTokens.rotate(token)).isInstanceOf(RefreshOutcome.Invalid.class);
	}

	@Test
	@DisplayName("a family past its maximum age stops refreshing even with a live token")
	void familyPastMaxAgeIsInvalid() {
		UUID userId = fixtures.user();
		String token = refreshTokens.issueNewFamily(userId).token();

		// The token itself is still well within its 7 days; the family started 31 days ago.
		// Without the family cap, an attacker holding a token could refresh forever.
		jdbc.sql("UPDATE refresh_token SET family_started_at = :then WHERE user_id = :id")
				.param("then", SqlTime.utc(Instant.now().minus(java.time.Duration.ofDays(31))))
				.param("id", userId)
				.update();

		assertThat(refreshTokens.rotate(token)).isInstanceOf(RefreshOutcome.Invalid.class);
	}

	@Test
	@DisplayName("a rotated token never expires later than its family allows")
	void rotationRespectsFamilyDeadline() {
		UUID userId = fixtures.user();
		String token = refreshTokens.issueNewFamily(userId).token();

		// Family started 28 days ago, cap is 30: the new token gets 2 days, not 7.
		jdbc.sql("UPDATE refresh_token SET family_started_at = :then WHERE user_id = :id")
				.param("then", SqlTime.utc(Instant.now().minus(java.time.Duration.ofDays(28))))
				.param("id", userId)
				.update();

		RefreshOutcome.Rotated rotated = (RefreshOutcome.Rotated) refreshTokens.rotate(token);

		assertThat(rotated.refreshTokenExpiresAt())
				.isBefore(Instant.now().plus(java.time.Duration.ofDays(3)));
	}

	@Test
	@DisplayName("rotation keeps the family and reports the current role")
	void rotationKeepsFamilyAndReportsRole() {
		UUID adminId = fixtures.adminUser();
		String token = refreshTokens.issueNewFamily(adminId).token();
		UUID familyBefore = familyOf(adminId);

		RefreshOutcome.Rotated rotated = (RefreshOutcome.Rotated) refreshTokens.rotate(token);

		assertThat(rotated.userId()).isEqualTo(adminId);
		assertThat(familyOf(adminId)).isEqualTo(familyBefore);
		// The role in the outcome comes from ADMIN_GOOGLE_SUBS, which is empty in the test
		// profile - so a stored ADMIN is demoted on refresh. That is the intended behaviour
		// (docs/dataflow.md section 3.2) and AuthEndpointsTest covers it from the API side.
		assertThat(rotated.role()).isEqualTo(UserRole.USER);
	}

	@Test
	@DisplayName("logout revokes the family; an unknown token is silently fine")
	void logoutRevokesFamily() {
		UUID userId = fixtures.user();
		String token = refreshTokens.issueNewFamily(userId).token();

		refreshTokens.revokeFamilyOf(token);

		assertThat(repository.countLiveInFamily(familyOf(userId))).isZero();
		assertThat(refreshTokens.rotate(token)).isInstanceOf(RefreshOutcome.ReuseDetected.class);

		// No exception, no row touched.
		refreshTokens.revokeFamilyOf("never-existed");
		refreshTokens.revokeFamilyOf(null);
	}

	@Test
	@DisplayName("two threads refreshing the same token: exactly one succeeds")
	void concurrentRotationHasOneWinner() throws Exception {
		UUID userId = fixtures.user();
		String token = refreshTokens.issueNewFamily(userId).token();

		// FOR UPDATE serialises the two transactions. The loser sees the revoked_at the
		// winner wrote and is treated as reuse, which revokes the family - matching the
		// design note that a client must share one refresh call per tab
		// (docs/dataflow.md section 3.4).
		CyclicBarrier bothReady = new CyclicBarrier(2);
		Callable<RefreshOutcome> attempt = () -> {
			bothReady.await(10, TimeUnit.SECONDS);
			return refreshTokens.rotate(token);
		};

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<RefreshOutcome> one = pool.submit(attempt);
			Future<RefreshOutcome> two = pool.submit(attempt);

			List<RefreshOutcome> outcomes = List.of(one.get(20, TimeUnit.SECONDS),
					two.get(20, TimeUnit.SECONDS));

			assertThat(outcomes).filteredOn(RefreshOutcome.Rotated.class::isInstance).hasSize(1);
			assertThat(outcomes).filteredOn(RefreshOutcome.ReuseDetected.class::isInstance)
					.hasSize(1);
			assertThat(repository.countLiveInFamily(familyOf(userId))).isZero();
		}
		finally {
			pool.shutdownNow();
		}
	}

	private UUID familyOf(UUID userId) {
		return jdbc.sql("SELECT family_id FROM refresh_token WHERE user_id = :id LIMIT 1")
				.param("id", userId)
				.query(UUID.class)
				.single();
	}

	private void expireToken(UUID userId) {
		jdbc.sql("UPDATE refresh_token SET expires_at = :then WHERE user_id = :id")
				.param("then", SqlTime.utc(Instant.now().minusSeconds(60)))
				.param("id", userId)
				.update();
	}
}
