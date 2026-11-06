package dev.saq.mediscan.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.support.PostgresTestBase;

/**
 * What {@code V3__accounts.sql} actually produced.
 *
 * <p>Two things are checked here that no amount of Java-side testing can establish. First, the
 * privacy promise: {@code app_user} and {@code refresh_token} must have no column that could
 * hold an email address, a photo URL, or a raw token (CLAUDE.md rule 13, LLD 13.3 case 29). A
 * column list is the only way to assert the absence of a field.
 *
 * <p>Second, the "exactly one owner" rule. It is a check constraint rather than a convention,
 * so the test writes directly through JDBC - bypassing the entity, which is the only way to
 * try the states the constraint exists to forbid.
 */
class AccountSchemaTest extends PostgresTestBase {

	@Autowired
	private JdbcClient jdbc;

	@Test
	@DisplayName("app_user stores no email, photo or token column")
	void appUserStoresMinimalIdentity() {
		assertThat(columnsOf("app_user")).containsExactlyInAnyOrder(
				"id", "google_sub", "display_name", "role", "created_at", "last_login_at");
	}

	@Test
	@DisplayName("refresh_token stores a hash, never a raw token")
	void refreshTokenStoresOnlyHashes() {
		List<String> columns = columnsOf("refresh_token");

		assertThat(columns).containsExactlyInAnyOrder(
				"id", "user_id", "family_id", "token_hash", "issued_at", "expires_at",
				"family_started_at", "revoked_at", "replaced_by");
		// Named "token_hash", not "token": the name is part of the guarantee.
		assertThat(columns).doesNotContain("token");
	}

	@Test
	@DisplayName("the token hash column matches session.token_hash from V1")
	void tokenHashTypeMatchesPhaseOne() {
		// Both hold SHA-256 hex from the same helper, so a drift here would mean the two
		// token types were hashed differently (LLD 4, 7.6).
		assertThat(columnType("refresh_token", "token_hash"))
				.isEqualTo(columnType("session", "token_hash"))
				.isEqualTo("character varying");
		assertThat(columnLength("refresh_token", "token_hash")).isEqualTo(64);
	}

	@Test
	@DisplayName("a report with two owners is rejected by the database")
	void rejectsTwoOwners() {
		UUID sessionId = insertSession();
		UUID userId = insertUser("two-owners-" + UUID.randomUUID());

		assertThatThrownBy(() -> insertReport(sessionId, userId))
				.hasMessageContaining("report_exactly_one_owner");
	}

	@Test
	@DisplayName("a report with no owner is rejected by the database")
	void rejectsNoOwner() {
		assertThatThrownBy(() -> insertReport(null, null))
				.hasMessageContaining("report_exactly_one_owner");
	}

	@Test
	@DisplayName("a report owned by only a user is accepted, now that session_id is nullable")
	void acceptsUserOnlyOwner() {
		UUID userId = insertUser("user-owner-" + UUID.randomUUID());

		assertThat(insertReport(null, userId)).isEqualTo(1);
	}

	@Test
	@DisplayName("deleting a user cascades to the reports they own")
	void deletingUserCascadesToReports() {
		UUID userId = insertUser("cascade-" + UUID.randomUUID());
		insertReport(null, userId);

		jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", userId).update();

		Long remaining = jdbc.sql("SELECT count(*) FROM report WHERE user_id = :id")
				.param("id", userId)
				.query(Long.class)
				.single();
		assertThat(remaining).isZero();
	}

	private List<String> columnsOf(String table) {
		return jdbc.sql("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = :table
				""")
				.param("table", table)
				.query(String.class)
				.list();
	}

	private String columnType(String table, String column) {
		return jdbc.sql("""
				SELECT data_type FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = :table AND column_name = :column
				""")
				.param("table", table)
				.param("column", column)
				.query(String.class)
				.single();
	}

	private int columnLength(String table, String column) {
		return jdbc.sql("""
				SELECT character_maximum_length FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = :table AND column_name = :column
				""")
				.param("table", table)
				.param("column", column)
				.query(Integer.class)
				.single();
	}

	private UUID insertSession() {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO session (id, token_hash, created_at, expires_at)
				VALUES (:id, :hash, :now, :expires)
				""")
				.param("id", id)
				.param("hash", UUID.randomUUID().toString().replace("-", "").repeat(2))
				.param("now", utc(Instant.now()))
				.param("expires", utc(Instant.now().plusSeconds(3600)))
				.update();
		return id;
	}

	private UUID insertUser(String googleSub) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO app_user (id, google_sub, display_name, role, created_at, last_login_at)
				VALUES (:id, :sub, 'User', 'USER', :now, :now)
				""")
				.param("id", id)
				.param("sub", googleSub)
				.param("now", utc(Instant.now()))
				.update();
		return id;
	}

	private int insertReport(UUID sessionId, UUID userId) {
		return jdbc.sql("""
				INSERT INTO report (id, session_id, user_id, source_type, status, created_at, expires_at)
				VALUES (:id, :sessionId, :userId, :source, 'PENDING', :now, :expires)
				""")
				.param("id", UUID.randomUUID())
				.param("sessionId", sessionId)
				.param("userId", userId)
				.param("source", SourceType.TEXT.name())
				.param("now", utc(Instant.now()))
				.param("expires", utc(Instant.now().plusSeconds(3600)))
				.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
