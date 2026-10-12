package dev.saq.mediscan.user;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.SqlTime;

/**
 * The sign-in write: find or create the user by Google {@code sub}, in one statement.
 *
 * <p>One statement rather than a read-then-write, because two tabs auto-signing in at the same
 * moment is a normal event (docs/dataflow.md section 3.3), and a check-then-insert would let
 * both see "no row" and then one of them would fail on the unique index. {@code ON CONFLICT
 * (google_sub) DO UPDATE} makes the loser update the winner's row instead.
 *
 * <p>{@code (xmax = 0)} is how Postgres lets you tell an insert from an update in a
 * {@code RETURNING} clause: {@code xmax} is zero on a freshly inserted row and non-zero on one
 * this transaction updated. That is the {@code newUser} flag in the API response, and getting
 * it from the same statement avoids a second query that could disagree with the first.
 *
 * <p>Uses {@link JdbcClient} rather than Spring Data, per {@code backend/CLAUDE.md}: upserts
 * and conditional updates are written as SQL, simple CRUD is not.
 */
@Component
public class UserUpsert {

	private final JdbcClient jdbc;

	public UserUpsert(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * What the upsert returned.
	 *
	 * @param userId the row's id, whether it was just created or already existed
	 * @param newUser true when this sign-in created the account
	 * @param displayName the stored display name
	 * @param role the stored role, which the caller has just recomputed from config
	 * @param createdAt when the account was first created, not when this sign-in happened
	 */
	public record Result(UUID userId, boolean newUser, String displayName, UserRole role,
			Instant createdAt) {

		/** Never prints {@link #displayName()}: it is a real person's name (LLD 12). */
		@Override
		public String toString() {
			return "Result[userId=" + userId + ", newUser=" + newUser + ", role=" + role + "]";
		}
	}

	/**
	 * Creates the user, or updates the existing row's name, role and last-login time.
	 *
	 * <p>The role is passed in already computed from {@code ADMIN_GOOGLE_SUBS}, so a promotion
	 * or demotion lands on the same statement as the sign-in rather than needing a second
	 * write (docs/dataflow.md section 3.2).
	 */
	public Result upsert(String googleSub, String displayName, UserRole role, Instant now) {
		return jdbc.sql("""
				INSERT INTO app_user (id, google_sub, display_name, role, created_at, last_login_at)
				VALUES (:newId, :sub, :name, :role, :now, :now)
				ON CONFLICT (google_sub) DO UPDATE
				    SET display_name = EXCLUDED.display_name,
				        role = EXCLUDED.role,
				        last_login_at = EXCLUDED.last_login_at
				RETURNING id, display_name, role, created_at, (xmax = 0) AS inserted
				""")
				.param("newId", UUID.randomUUID())
				.param("sub", googleSub)
				.param("name", displayName)
				.param("role", role.name())
				// SqlTime because the PostgreSQL driver cannot infer a SQL type for Instant.
				.param("now", SqlTime.utc(now))
				.query((rs, rowNum) -> new Result(
						rs.getObject("id", UUID.class),
						rs.getBoolean("inserted"),
						rs.getString("display_name"),
						UserRole.valueOf(rs.getString("role")),
						rs.getTimestamp("created_at").toInstant()))
				.single();
	}
}
