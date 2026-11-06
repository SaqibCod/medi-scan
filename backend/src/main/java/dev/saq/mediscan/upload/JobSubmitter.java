package dev.saq.mediscan.upload;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import jakarta.annotation.PreDestroy;

/**
 * Hands work to the job pool, and reports whether the pool has room.
 *
 * <p>Capacity is checked twice, for two different reasons. {@link #hasCapacity()} is asked
 * before a temp file is written or a report row is inserted, so a busy server does not leave
 * either behind. {@link #submit} can still be rejected afterwards - another request can take
 * the last slot in between - and the caller undoes its own work in that case.
 */
@Component
public class JobSubmitter {

	private static final Logger log = LoggerFactory.getLogger(JobSubmitter.class);

	private final ThreadPoolExecutor executor;
	private final Duration shutdownGrace;

	public JobSubmitter(ThreadPoolExecutor reportJobExecutor, MediScanProperties properties) {
		this.executor = reportJobExecutor;
		this.shutdownGrace = properties.jobs().shutdownGrace();
	}

	/**
	 * Whether the queue has room right now.
	 *
	 * <p>Advisory: it can be false by the time {@link #submit} runs. It exists so the common
	 * case of a full queue is rejected before any state is created, not to make submission
	 * infallible.
	 */
	public boolean hasCapacity() {
		return executor.getQueue().remainingCapacity() > 0;
	}

	/**
	 * Queues a job.
	 *
	 * @return {@code false} when the pool refused it, in which case the caller must roll back
	 *     whatever it created for this job
	 */
	public boolean submit(Runnable job) {
		try {
			executor.execute(job);
			return true;
		}
		catch (RejectedExecutionException ex) {
			// Expected under load, not an error: AbortPolicy is doing what it was chosen for.
			log.info("Report job rejected: queue full");
			return false;
		}
	}

	/** Queued jobs, for the stats endpoint and tests. */
	public int queueDepth() {
		return executor.getQueue().size();
	}

	/**
	 * Lets running jobs finish before the JVM exits.
	 *
	 * <p>A job interrupted mid-flight leaves a {@code PROCESSING} row and possibly a temp
	 * file. Waiting lets it reach a terminal status of its own; anything still running when
	 * the grace period expires is picked up by {@code StartupRecovery} on the next start and
	 * failed as {@code INTERRUPTED}.
	 */
	@PreDestroy
	void awaitRunningJobs() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
				log.warn("Shutdown grace expired with {} jobs still running; "
						+ "they will be marked INTERRUPTED on the next start",
						executor.getActiveCount());
				executor.shutdownNow();
			}
		}
		catch (InterruptedException ex) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
