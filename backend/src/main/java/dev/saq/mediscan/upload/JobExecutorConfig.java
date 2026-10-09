package dev.saq.mediscan.upload;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * The pool that runs report jobs.
 *
 * <p>Deliberately small and bounded. Two workers and a queue of twenty on a 2 GB box, with
 * {@link ThreadPoolExecutor.AbortPolicy}, so that a burst of uploads produces an honest
 * {@code 429 BUSY} instead of a backlog that grows until the JVM is killed. An unbounded queue
 * would turn a load spike into an outage, and a caller told "busy, retry" is in a far better
 * position than one whose report sits in a queue for ten minutes
 * ({@code backend/CLAUDE.md}, "Performance and memory").
 *
 * <p>Not virtual threads. These jobs are CPU and memory bound - PDF parsing, regex passes over
 * the whole document, JSON parsing - and the limit that matters is how many can be in flight at
 * once without exhausting the heap. Virtual threads would remove exactly the bound this needs.
 *
 * <p>Declared as a {@code @Bean} with a destroy method rather than Spring's
 * {@code ThreadPoolTaskExecutor} because {@link JobSubmitter} needs the queue's remaining
 * capacity to answer "is there room?" before a temp file is written.
 */
@Configuration
public class JobExecutorConfig {

	/**
	 * @param properties worker count, queue capacity and the shutdown grace period
	 */
	@Bean(destroyMethod = "shutdown")
	public ThreadPoolExecutor reportJobExecutor(MediScanProperties properties) {
		MediScanProperties.Jobs jobs = properties.jobs();

		return new ThreadPoolExecutor(
				jobs.workers(),
				jobs.workers(),
				// Irrelevant with core == max, but required by the constructor.
				0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(jobs.queueCapacity()),
				new ReportJobThreadFactory(),
				// The whole point: a full queue is rejected, not absorbed.
				new ThreadPoolExecutor.AbortPolicy());
	}

	/**
	 * Names the worker threads {@code report-job-1}, {@code report-job-2}, and so on.
	 *
	 * <p>Worth the class: these threads appear in every log line's thread field and in any
	 * thread dump taken from production, and {@code pool-2-thread-1} identifies nothing.
	 */
	private static final class ReportJobThreadFactory implements ThreadFactory {

		private final AtomicInteger counter = new AtomicInteger(1);

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "report-job-" + counter.getAndIncrement());
			// Not a daemon: a job holds a temp file and a PROCESSING report row, so it should
			// get its shutdown grace period rather than being killed mid-write. Anything
			// still unfinished after that becomes INTERRUPTED on the next start.
			thread.setDaemon(false);
			return thread;
		}
	}
}
