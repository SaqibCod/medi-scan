package dev.saq.mediscan.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need the application and a database.
 *
 * <p>Runs against real Postgres with pgvector rather than an in-memory database, so the Flyway
 * migrations, the {@code vector} extension and {@code timestamptz} behaviour are exercised by
 * the tests instead of being assumed.
 *
 * <p>The container is a started-once singleton rather than a {@code @Container} field or a
 * {@code @ServiceConnection} bean. Both of those are tied to a context or a class lifecycle,
 * and a test that overrides a property gets its own application context - which would start a
 * second database. One static container shared by every context keeps the suite to a single
 * Postgres start. It is never stopped explicitly; Testcontainers' Ryuk sidecar removes it when
 * the JVM exits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class PostgresTestBase {

	/** The same image the local compose file uses, so tests and development match. */
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}
}
