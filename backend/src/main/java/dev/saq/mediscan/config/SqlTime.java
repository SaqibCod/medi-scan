package dev.saq.mediscan.config;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Converts an {@link Instant} into the type the PostgreSQL JDBC driver accepts for a
 * {@code timestamptz} parameter.
 *
 * <p>The driver cannot infer a SQL type for {@code Instant} and fails with "Can't infer the
 * SQL type to use for an instance of java.time.Instant". JPA hides this because Hibernate
 * knows the mapping, so the problem only shows up in the hand-written {@link
 * org.springframework.jdbc.core.simple.JdbcClient} statements - the conditional status
 * updates, the daily cap, and the stats upserts.
 *
 * <p>Always UTC, matching {@link ClockConfig} and the {@code hibernate.jdbc.time_zone}
 * setting, so a value written through JDBC and one written through JPA mean the same instant.
 */
public final class SqlTime {

	private SqlTime() {
	}

	/** {@code instant} as a UTC {@link OffsetDateTime}, for binding to {@code timestamptz}. */
	public static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
