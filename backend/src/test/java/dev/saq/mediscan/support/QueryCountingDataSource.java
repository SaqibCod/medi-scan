package dev.saq.mediscan.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Counts {@code prepareStatement} calls on the real connection, to catch an N+1 query
 * regression in a test without mocking the database ({@code backend/CLAUDE.md}, "Testing").
 *
 * <p>Each {@link org.springframework.jdbc.core.simple.JdbcClient} {@code .query()} or
 * {@code .update()} call prepares exactly one statement, so the count is the number of
 * round trips a test's code actually made.
 */
public class QueryCountingDataSource extends DelegatingDataSource {

	private final AtomicInteger count = new AtomicInteger();

	public QueryCountingDataSource(DataSource target) {
		super(target);
	}

	public void reset() {
		count.set(0);
	}

	public int count() {
		return count.get();
	}

	@Override
	public Connection getConnection() throws SQLException {
		return wrap(getTargetDataSource().getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return wrap(getTargetDataSource().getConnection(username, password));
	}

	private Connection wrap(Connection real) {
		return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class },
				(proxy, method, args) -> {
					if (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")) {
						count.incrementAndGet();
					}
					try {
						return method.invoke(real, args);
					}
					catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				});
	}
}
