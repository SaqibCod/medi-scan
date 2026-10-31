package dev.saq.mediscan.extract;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Lets one page be OCR'd at a time, and gives each report a total OCR budget.
 *
 * <p><strong>One permit.</strong> OCR is the most memory-hungry step in the pipeline and the
 * box has 2 GB. Two jobs each holding a decoded page plus a Tesseract process is how this
 * becomes an out-of-memory kill, so the permit is the thing that makes the memory arithmetic in
 * {@code docs/phase-4-lld.md} 6.1 true rather than hopeful. The job pool has two workers, so
 * without it two OCR runs really would overlap.
 *
 * <p><strong>A budget per report, and waiting counts against it.</strong> A ten-page scan
 * behind another ten-page scan would otherwise sit in the queue well past the two minutes the
 * client polls for, and the user would watch a spinner until it gave up. Counting the wait means
 * a report that cannot finish in time fails saying so instead. The page timeout is also capped
 * by whatever budget is left, so the last page cannot overshoot the total.
 *
 * <p>The permit is released in a {@code finally}. A page that throws must not take the
 * application's only OCR slot with it.
 */
@Component
class OcrGate {

	private static final Logger log = LoggerFactory.getLogger(OcrGate.class);

	/** Fair, so a report already waiting is not starved by one that arrives later. */
	private final Semaphore permit = new Semaphore(1, true);

	private final Duration totalBudget;
	private final Duration pageTimeout;
	private final Clock clock;

	OcrGate(MediScanProperties properties, Clock clock) {
		this.totalBudget = properties.ocr().totalTimeout();
		this.pageTimeout = properties.ocr().pageTimeout();
		this.clock = clock;
	}

	/** Starts a report's OCR budget. One session per report, however many pages it has. */
	Session open() {
		return new Session();
	}

	/** What one page's OCR does once it holds the permit. */
	interface PageWork<T> {

		/**
		 * @param timeout how long this page may take: the page timeout, or whatever is left of
		 *     the report's budget if that is less
		 */
		T run(Duration timeout);
	}

	/**
	 * One report's claim on the OCR slot.
	 *
	 * <p>Not thread-safe, and does not need to be: a report is processed by one worker thread.
	 */
	final class Session {

		private final Instant startedAt = clock.instant();

		private int pages;
		private long waitMs;
		private long ocrMs;

		/**
		 * Runs one page's OCR, holding the permit for exactly that long.
		 *
		 * @throws OcrFailedException with {@code BUDGET_EXHAUSTED} when the report has spent its
		 *     total OCR time, including time spent waiting for the permit
		 */
		<T> T runPage(PageWork<T> work) {
			Duration remaining = remaining();
			if (remaining.isNegative() || remaining.isZero()) {
				throw new OcrFailedException(OcrFailedException.Reason.BUDGET_EXHAUSTED);
			}

			long waitStarted = System.nanoTime();
			boolean acquired;
			try {
				// Bounded by what is left of the budget, so waiting cannot outlast it.
				acquired = permit.tryAcquire(remaining.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new OcrFailedException(OcrFailedException.Reason.INTERRUPTED);
			}
			waitMs += (System.nanoTime() - waitStarted) / 1_000_000;

			if (!acquired) {
				throw new OcrFailedException(OcrFailedException.Reason.BUDGET_EXHAUSTED);
			}

			try {
				// Recomputed after the wait: the queue may have eaten most of the budget, and
				// the page must not be allowed to spend time the report no longer has.
				Duration left = remaining();
				if (left.isNegative() || left.isZero()) {
					throw new OcrFailedException(OcrFailedException.Reason.BUDGET_EXHAUSTED);
				}

				long ocrStarted = System.nanoTime();
				try {
					return work.run(pageTimeout.compareTo(left) <= 0 ? pageTimeout : left);
				}
				finally {
					ocrMs += (System.nanoTime() - ocrStarted) / 1_000_000;
					pages++;
				}
			}
			finally {
				// Always. A page that threw must not keep the application's only OCR slot.
				permit.release();
			}
		}

		/** How much of the report's OCR budget is left. */
		Duration remaining() {
			return totalBudget.minus(Duration.between(startedAt, clock.instant()));
		}

		/** Numbers only, for the job's log line. Nothing here is report content. */
		void logSummary(java.util.UUID reportId) {
			log.info("ocr report done id={} pages={} waitMs={} ocrMs={}",
					reportId, pages, waitMs, ocrMs);
		}

		int pages() {
			return pages;
		}

		long waitMs() {
			return waitMs;
		}

		long ocrMs() {
			return ocrMs;
		}
	}
}
