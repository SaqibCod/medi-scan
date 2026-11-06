package dev.saq.mediscan.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.support.TestProperties;

/**
 * The bounded job pool ({@code LLD} 6.5).
 *
 * <p>The behaviour under test is refusal. A pool that silently absorbs work would turn a load
 * spike into an out-of-memory kill on a 2 GB box, so {@link JobSubmitter} has to say no - and
 * say it both before work is created ({@link JobSubmitter#hasCapacity()}) and when it loses a
 * race for the last slot ({@link JobSubmitter#submit}).
 *
 * <p>Latches rather than sleeps, per {@code backend/CLAUDE.md}: the test blocks workers
 * deterministically instead of hoping a timing window holds.
 */
class JobSubmitterTest {

	/** One worker and a queue of two, so the limit is three jobs before refusal. */
	private final MediScanProperties properties = TestProperties.withJobs(1, 2);

	private final ThreadPoolExecutor executor =
			new JobExecutorConfig().reportJobExecutor(properties);

	private final JobSubmitter submitter = new JobSubmitter(executor, properties);

	private final CountDownLatch release = new CountDownLatch(1);

	@AfterEach
	void releaseWorkers() {
		release.countDown();
		executor.shutdownNow();
	}

	@Test
	@DisplayName("accepts jobs while the queue has room")
	void acceptsUntilFull() {
		assertThat(submitter.hasCapacity()).isTrue();

		// One occupies the single worker, two fill the queue.
		assertThat(submitter.submit(blockingJob())).isTrue();
		assertThat(submitter.submit(blockingJob())).isTrue();
		assertThat(submitter.submit(blockingJob())).isTrue();
	}

	@Test
	@DisplayName("reports no capacity once the queue is full")
	void reportsFullQueue() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		submitter.submit(blockingJob(started));
		// Wait for the worker to actually pick the first job up, so the next two land in the
		// queue rather than racing the worker for it.
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		submitter.submit(blockingJob());
		submitter.submit(blockingJob());

		// This is what CreateReportService checks before writing a temp file or inserting a
		// row, so that a busy server leaves neither behind.
		assertThat(submitter.hasCapacity()).isFalse();
		assertThat(submitter.queueDepth()).isEqualTo(2);
	}

	@Test
	@DisplayName("refuses a job when the queue is full instead of absorbing it")
	void refusesWhenFull() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		submitter.submit(blockingJob(started));
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		submitter.submit(blockingJob());
		submitter.submit(blockingJob());

		// AbortPolicy doing its job: false, not an exception and not an unbounded queue.
		assertThat(submitter.submit(blockingJob())).isFalse();
	}

	@Test
	@DisplayName("accepts again once a worker frees up")
	void recoversAfterDraining() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);

		executor.execute(() -> {
			started.countDown();
			await(finish);
		});
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		submitter.submit(() -> {
		});
		submitter.submit(() -> {
		});
		assertThat(submitter.hasCapacity()).isFalse();

		finish.countDown();

		// The queued jobs are trivial, so the pool drains as soon as the worker is released.
		org.awaitility.Awaitility.await()
				.atMost(Duration.ofSeconds(5))
				.until(submitter::hasCapacity);
	}

	@Test
	@DisplayName("names its worker threads so logs and thread dumps identify them")
	void namesWorkerThreads() throws Exception {
		CountDownLatch named = new CountDownLatch(1);
		StringBuilder threadName = new StringBuilder();

		submitter.submit(() -> {
			threadName.append(Thread.currentThread().getName());
			named.countDown();
		});

		assertThat(named.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(threadName.toString()).startsWith("report-job-");
	}

	private Runnable blockingJob() {
		return blockingJob(new CountDownLatch(1));
	}

	private Runnable blockingJob(CountDownLatch started) {
		return () -> {
			started.countDown();
			await(release);
		};
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
