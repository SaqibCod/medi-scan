package dev.saq.mediscan.support;

import org.springframework.stereotype.Component;

/**
 * Holds the {@link QueryCountingDataSource} {@link QueryCountingDataSourceInstaller} wraps the
 * real {@code dataSource} bean in.
 *
 * <p>A permanent {@code @Component} rather than something imported per test: an extra
 * {@code @TestConfiguration} with its own bean definitions gives Spring Test a new context
 * signature, and therefore a brand new Testcontainers-backed Hikari pool. With ~10 other
 * property-driven context variants already in this suite, one more pushed the shared Postgres
 * container past its {@code max_connections} during a full {@code ./mvnw verify}. Scanned into
 * the one default test context instead, the counter costs nothing extra.
 *
 * <p>Not autowired by type directly: Spring still reports the production bean's declared
 * type ({@code DataSource}) for autowiring purposes even after a {@code BeanPostProcessor}
 * swaps in a decorator instance, so a test resolves the decorator through this holder instead.
 */
@Component
public class QueryCountingDataSourceHolder {

	private QueryCountingDataSource delegate;

	public void set(QueryCountingDataSource delegate) {
		this.delegate = delegate;
	}

	public void reset() {
		delegate.reset();
	}

	public int count() {
		return delegate.count();
	}
}
