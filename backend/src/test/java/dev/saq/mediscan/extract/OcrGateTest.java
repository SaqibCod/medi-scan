package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.support.TestProperties;

/**
 * {@link OcrGate}: one page at a time, and a budget per report that counts waiting.
 *
 * <p>The budget tests drive an advanceable clock rather than sleeping, so they assert the
 * accounting rather than the wall clock ({@code backend/CLAUDE.md}: "No sleeping in tests").
 * The concurrency tests use latches for the same reason.
 */
class OcrGateTest {

	private static OcrGate gate(Clock clock) {
		return gate(clock, Duration.ofSeconds(45), Duration.ofSeconds(150));
	}

	private static OcrGate gate(Clock clock, Duration pageTimeout, Duration totalTimeout) {
		return new OcrGate(TestProperties.withOcrLimits(10, pageTimeout, totalTimeout), clock);
	}

	@Test
	@DisplayName("a page gets the page timeout when the budget is ample")
	void passesPageTimeout() {
		OcrGate.Session session = gate(Clock.systemUTC()).open();

		Duration given = session.runPage(timeout -> timeout);

		assertThat(given).isEqualTo(Duration.ofSeconds(45));
	}

	@Test
	@DisplayName("the page timeout is capped by what is left of the report's budget")
	void pageTimeoutCappedByRemainingBudget() {
		// Thirty seconds of a forty-second budget already gone, so the next page may have ten,
		// not the forty-five the page timeout would allow. Without this the last page of a
		// slow report could overshoot the total by its whole timeout.
		MutableClock clock = new MutableClock();
		OcrGate.Session session = gate(clock, Duration.ofSeconds(45), Duration.ofSeconds(40))
				.open();
		clock.advance(Duration.ofSeconds(30));

		Duration given = session.runPage(timeout -> timeout);

		assertThat(given).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	@DisplayName("a report that has spent its budget is refused, and no work runs")
	void budgetExhaustedRefusesTheWork() {
		MutableClock clock = new MutableClock();
		OcrGate.Session session = gate(clock, Duration.ofSeconds(45), Duration.ofSeconds(150))
				.open();
		AtomicInteger ran = new AtomicInteger();

		clock.advance(Duration.ofSeconds(151));

		assertThatThrownBy(() -> session.runPage(timeout -> ran.incrementAndGet()))
				.isInstanceOf(OcrFailedException.class)
				.extracting(failure -> ((OcrFailedException) failure).reason())
				.isEqualTo(OcrFailedException.Reason.BUDGET_EXHAUSTED);

		assertThat(ran.get()).as("the remaining pages are skipped, not attempted").isZero();
	}

	@Test
	@DisplayName("the budget is spent by earlier pages, so a later page is refused")
	void budgetIsSpentAcrossPages() {
		MutableClock clock = new MutableClock();
		OcrGate.Session session = gate(clock, Duration.ofSeconds(45), Duration.ofSeconds(100))
				.open();

		// Two pages at 60 seconds each: the first leaves 40 of the 100-second budget, the
		// second takes the report past it. A third must not be attempted.
		for (int page = 0; page < 2; page++) {
			session.runPage(timeout -> {
				clock.advance(Duration.ofSeconds(60));
				return null;
			});
		}

		assertThat(session.pages()).isEqualTo(2);
		assertThat(session.remaining()).isNegative();
		assertThatThrownBy(() -> session.runPage(timeout -> null))
				.isInstanceOf(OcrFailedException.class)
				.extracting(failure -> ((OcrFailedException) failure).reason())
				.isEqualTo(OcrFailedException.Reason.BUDGET_EXHAUSTED);
	}

	@Test
	@DisplayName("each report gets its own budget")
	void budgetIsPerReport() {
		MutableClock clock = new MutableClock();
		OcrGate shared = gate(clock, Duration.ofSeconds(45), Duration.ofSeconds(100));

		OcrGate.Session first = shared.open();
		first.runPage(timeout -> {
			clock.advance(Duration.ofSeconds(99));
			return null;
		});

		// A second report opened now starts with a full budget, even though the first has
		// nearly none left.
		OcrGate.Session second = shared.open();
		assertThat(second.remaining()).isEqualTo(Duration.ofSeconds(100));
		assertThat(first.remaining()).isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	@DisplayName("the permit is released when a page throws")
	void permitReleasedOnFailure() {
		OcrGate gate = gate(Clock.systemUTC());
		OcrGate.Session session = gate.open();

		assertThatThrownBy(() -> session.runPage(timeout -> {
			throw new IllegalStateException("page blew up");
		})).isInstanceOf(IllegalStateException.class);

		// If the permit had leaked, this would block until the budget ran out and then fail.
		String second = gate.open().runPage(timeout -> "second page ran");
		assertThat(second).isEqualTo("second page ran");
	}

	@Test
	@DisplayName("the permit is released when a page fails with an OCR failure")
	void permitReleasedOnOcrFailure() {
		OcrGate gate = gate(Clock.systemUTC());

		assertThatThrownBy(() -> gate.open().runPage(timeout -> {
			throw new OcrFailedException(OcrFailedException.Reason.TIMEOUT);
		})).isInstanceOf(OcrFailedException.class);

		String after = gate.open().runPage(timeout -> "ran");
		assertThat(after).isEqualTo("ran");
	}

	@Test
	@DisplayName("two reports never OCR at the same time")
	void onlyOnePageAtATime() throws Exception {
		OcrGate gate = gate(Clock.systemUTC());
		AtomicInteger concurrent = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		CountDownLatch bothStarted = new CountDownLatch(2);
		List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());

		Runnable report = () -> gate.open().runPage(timeout -> {
			int running = concurrent.incrementAndGet();
			peak.accumulateAndGet(running, Math::max);
			order.add("in");
			try {
				// Long enough that a second thread would overlap if the permit let it.
				Thread.sleep(150);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			order.add("out");
			concurrent.decrementAndGet();
			bothStarted.countDown();
			return null;
		});

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			pool.submit(report);
			pool.submit(report);
			assertThat(bothStarted.await(30, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(peak.get()).as("the whole point of the permit").isEqualTo(1);
		// Strictly serialised: one page finishes before the next begins.
		assertThat(order).containsExactly("in", "out", "in", "out");
	}

	@Test
	@DisplayName("waiting for the permit counts against the waiting report's budget")
	void waitingCountsAgainstTheBudget() throws Exception {
		// The second report's budget is shorter than the first report's page, so it should give
		// up waiting rather than queue indefinitely. This is the case that keeps a queued
		// report from sitting past the client's polling window.
		OcrGate gate = gate(Clock.systemUTC(), Duration.ofSeconds(45), Duration.ofMillis(300));
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			pool.submit(() -> gate.open().runPage(timeout -> {
				holding.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return null;
			}));

			assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> gate.open().runPage(timeout -> "never runs"))
					.isInstanceOf(OcrFailedException.class)
					.extracting(failure -> ((OcrFailedException) failure).reason())
					.isEqualTo(OcrFailedException.Reason.BUDGET_EXHAUSTED);

			release.countDown();
		}
	}

	@Test
	@DisplayName("the session records pages, wait time and OCR time as numbers")
	void recordsTimings() {
		OcrGate.Session session = gate(Clock.systemUTC()).open();

		session.runPage(timeout -> null);
		session.runPage(timeout -> null);

		assertThat(session.pages()).isEqualTo(2);
		assertThat(session.waitMs()).isNotNegative();
		assertThat(session.ocrMs()).isNotNegative();
	}

	/** A clock the test moves on purpose, so budget accounting is asserted without sleeping. */
	private static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-10-31T09:00:00Z");

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
