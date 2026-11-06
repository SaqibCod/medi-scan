package dev.saq.mediscan.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import dev.saq.mediscan.support.PostgresTestBase;

/**
 * The daily LLM cap ({@code LLD} 8.4).
 *
 * <p>This is the only thing between a portfolio demo and a bill, so the test that matters is
 * the concurrent one: many threads racing the counter must not be able to push it past the
 * cap. A read-then-write implementation passes every sequential test here and fails that one.
 */
@TestPropertySource(properties = "mediscan.llm.daily-cap=5")
class DailyCapGuardTest extends PostgresTestBase {

	@Autowired
	DailyCapGuard capGuard;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void resetCounter() {
		jdbc.sql("delete from llm_usage").update();
	}

	@Test
	@DisplayName("allows calls up to the cap and refuses the next")
	void allowsUpToCap() {
		for (int call = 1; call <= 5; call++) {
			assertThat(capGuard.tryAcquire())
					.as("call %d of 5", call)
					.isTrue();
		}

		assertThat(capGuard.tryAcquire()).isFalse();
		assertThat(capGuard.usedToday()).isEqualTo(5);
	}

	@Test
	@DisplayName("counts every acquisition, so the stored count matches what was allowed")
	void countsAcquisitions() {
		assertThat(capGuard.usedToday()).isZero();

		capGuard.tryAcquire();
		capGuard.tryAcquire();

		assertThat(capGuard.usedToday()).isEqualTo(2);
	}

	@Test
	@DisplayName("isCapReached flips only once the cap is actually spent")
	void reportsCapReached() {
		assertThat(capGuard.isCapReached()).isFalse();

		for (int call = 0; call < 4; call++) {
			capGuard.tryAcquire();
		}
		// Four of five: still room, so an upload must not be refused yet.
		assertThat(capGuard.isCapReached()).isFalse();

		capGuard.tryAcquire();
		assertThat(capGuard.isCapReached()).isTrue();
	}

	@Test
	@DisplayName("a refused acquisition does not increment the counter")
	void refusalDoesNotCount() {
		for (int call = 0; call < 5; call++) {
			capGuard.tryAcquire();
		}

		capGuard.tryAcquire();
		capGuard.tryAcquire();

		// Otherwise the counter would drift past the cap and the number shown on the admin
		// dashboard would be a lie.
		assertThat(capGuard.usedToday()).isEqualTo(5);
	}

	@Test
	@DisplayName("concurrent acquisition never exceeds the cap")
	void concurrentAcquisitionRespectsCap() throws Exception {
		List<Callable<Boolean>> attempts = IntStream.range(0, 32)
				.mapToObj(i -> (Callable<Boolean>) capGuard::tryAcquire)
				.toList();

		try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
			long granted = pool.invokeAll(attempts).stream()
					.map(future -> {
						try {
							return future.get();
						}
						catch (Exception ex) {
							throw new IllegalStateException(ex);
						}
					})
					.filter(Boolean::booleanValue)
					.count();

			// Exactly the cap: not fewer (the UPDATE must not lose a winner) and not more
			// (32 threads must not all read calls < cap and all proceed).
			assertThat(granted).isEqualTo(5);
		}

		assertThat(capGuard.usedToday()).isEqualTo(5);
	}

	@Test
	@DisplayName("exposes the configured cap")
	void exposesCap() {
		assertThat(capGuard.cap()).isEqualTo(5);
	}
}
