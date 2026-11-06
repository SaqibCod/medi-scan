package dev.saq.mediscan.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * The global daily LLM budget.
 *
 * <p>This is the only thing standing between a portfolio demo and a bill, so it is enforced in
 * the database rather than in memory: one atomic conditional {@code UPDATE} per attempt, whose
 * result decides whether the call happens ({@code CLAUDE.md} rule 8). A counter in a field
 * would be lost on restart and would not hold if a second instance ever ran.
 *
 * <p>Every <em>attempt</em> is counted, including retries. A retry costs the provider the same
 * as a first try, so counting only successes would let a failing provider spend the budget
 * several times over.
 *
 * <p>The day is the UTC date from the injected clock, matching {@code llm_usage.day}.
 */
@Component
public class DailyCapGuard {

	private final JdbcClient jdbc;
	private final Clock clock;
	private final int cap;

	public DailyCapGuard(JdbcClient jdbc, Clock clock, MediScanProperties properties) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.cap = properties.llm().dailyCap();
	}

	/**
	 * Takes one call from today's budget.
	 *
	 * <p>Two statements, in this order. The insert makes sure today's row exists without
	 * disturbing a count already there; the update both tests the cap and increments it in one
	 * statement, so two concurrent workers cannot both see {@code calls = cap - 1} and both
	 * proceed. The row count is the answer.
	 *
	 * @return {@code true} when the call may proceed; {@code false} when today's cap is reached
	 */
	public boolean tryAcquire() {
		LocalDate today = today();

		jdbc.sql("insert into llm_usage (day, calls) values (:day, 0) on conflict (day) do nothing")
				.param("day", today)
				.update();

		int updated = jdbc.sql("""
				update llm_usage
				   set calls = calls + 1
				 where day = :day and calls < :cap
				""")
				.param("day", today)
				.param("cap", cap)
				.update();

		return updated == 1;
	}

	/**
	 * Today's count against the cap, without taking anything.
	 *
	 * <p>Used by the upload pre-check so a caller is told {@code 429 CAPACITY} before a report
	 * is created, rather than getting a {@code 202} for a report that cannot possibly finish.
	 * Deliberately a plain read and therefore slightly stale: {@link #tryAcquire()} is the
	 * enforcement point, and this is only an early courtesy.
	 */
	public boolean isCapReached() {
		return usedToday() >= cap;
	}

	/** Calls recorded today. */
	public int usedToday() {
		Optional<Integer> calls = jdbc.sql("select calls from llm_usage where day = :day")
				.param("day", today())
				.query(Integer.class)
				.optional();
		return calls.orElse(0);
	}

	public int cap() {
		return cap;
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(ZoneOffset.UTC));
	}
}
