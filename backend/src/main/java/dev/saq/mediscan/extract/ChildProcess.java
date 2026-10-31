package dev.saq.mediscan.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external program safely, and nothing else.
 *
 * <p>Separate from the OCR engine so there is one audited implementation of the four things
 * that go wrong when a JVM starts a process, rather than one per caller:
 *
 * <ol>
 * <li><strong>No shell.</strong> The command is a list of arguments handed straight to
 * {@link ProcessBuilder}. A shell string would make a file name containing {@code ;} or
 * {@code $(...)} executable, and the file names here come from uploads.</li>
 * <li><strong>Both pipes are drained concurrently.</strong> A process that writes more than the
 * operating system's pipe buffer to a stream nobody is reading blocks forever. Tesseract writes
 * to both stdout and stderr, so draining one and then waiting is a deadlock waiting for a big
 * enough page - which is why the reads start before {@code waitFor} and run on their own
 * threads.</li>
 * <li><strong>Output is capped.</strong> The reader stops at {@code stdoutCap} and kills the
 * process, so a program that decides to print forever bounds the heap instead of filling
 * it.</li>
 * <li><strong>Timeouts end in a kill.</strong> {@code waitFor} with a deadline, then
 * {@link Process#destroyForcibly()}, then a short bounded wait to reap it. A process that
 * ignores the deadline does not become a permanent thread.</li>
 * </ol>
 *
 * <p><strong>stderr is read but never returned as text.</strong> Only its length comes back.
 * Tesseract puts the input path into its error messages - verified: {@code Error, cannot read
 * input file /tmp/x.png} - so returning it would hand a caller a file name to log, and
 * {@code CLAUDE.md} rule 3 forbids logging one.
 */
final class ChildProcess {

	/** Enough stderr to know something was said, little enough to never matter. */
	private static final int STDERR_CAP = 8 * 1024;

	/** How long to wait for a killed process to actually die, and for readers to finish. */
	private static final Duration REAP_GRACE = Duration.ofSeconds(2);

	private ChildProcess() {
	}

	/**
	 * Runs {@code command} and collects its stdout.
	 *
	 * @param command the program and its arguments, already split. Never a shell string
	 * @param environment extra environment variables for the child
	 * @param timeout how long the process may run before it is killed
	 * @param stdoutCap bytes of stdout to accept before killing the process
	 * @throws OcrFailedException if the process could not start, overran its timeout, overran
	 *     its output cap, or the calling thread was interrupted
	 */
	static Result run(List<String> command, Map<String, String> environment, Duration timeout,
			int stdoutCap) {

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.environment().putAll(environment);

		Process process;
		try {
			process = builder.start();
		}
		catch (IOException ex) {
			// The program is missing or not executable. The message can hold the path, so only
			// the exception type is ever surfaced.
			throw new OcrFailedException(OcrFailedException.Reason.START_FAILED);
		}

		Pipe stdout = new Pipe(process.getInputStream(), stdoutCap, process);
		Pipe stderr = new Pipe(process.getErrorStream(), STDERR_CAP, null);

		Thread stdoutReader = startDaemon(stdout, "ocr-stdout");
		Thread stderrReader = startDaemon(stderr, "ocr-stderr");

		try {
			boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!exited) {
				throw new OcrFailedException(OcrFailedException.Reason.TIMEOUT);
			}

			// Joined after the process exits so the readers see end-of-stream. Bounded, so a
			// reader wedged on a pipe that never closes cannot hold the job thread.
			stdoutReader.join(REAP_GRACE.toMillis());
			stderrReader.join(REAP_GRACE.toMillis());

			if (stdout.truncated()) {
				// The reader already killed the process; this is the reason it died.
				throw new OcrFailedException(OcrFailedException.Reason.OUTPUT_CAP);
			}

			return new Result(process.exitValue(), stdout.text(), stderr.length());
		}
		catch (InterruptedException ex) {
			// Shutdown, almost certainly. Restore the flag so the job notices too.
			Thread.currentThread().interrupt();
			throw new OcrFailedException(OcrFailedException.Reason.INTERRUPTED);
		}
		finally {
			// Covers the timeout path, the interrupt path, and a process that somehow outlived
			// its own exit. destroyForcibly on an already-dead process is a no-op.
			if (process.isAlive()) {
				process.destroyForcibly();
				try {
					process.waitFor(REAP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private static Thread startDaemon(Pipe pipe, String name) {
		Thread thread = new Thread(pipe, name);
		// Daemon: a reader must never be the reason the JVM stays up.
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/**
	 * What the process said and how it ended.
	 *
	 * @param exitCode the process's exit code
	 * @param stdout everything it printed to stdout, up to the cap
	 * @param stderrLength how many bytes it printed to stderr. The bytes themselves are
	 *     deliberately dropped rather than carried: see the class comment
	 */
	record Result(int exitCode, String stdout, int stderrLength) {

		/** Hides {@link #stdout()}, which for an OCR run is the text of someone's report. */
		@Override
		public String toString() {
			return "Result[exitCode=" + exitCode + ", stdoutChars=" + stdout.length()
					+ ", stderrBytes=" + stderrLength + "]";
		}
	}

	/**
	 * Drains one stream into a capped buffer.
	 *
	 * <p>On exceeding the cap it kills the process, if it was given one. Killing rather than
	 * merely stopping the read matters: a program still writing to a pipe nobody drains blocks,
	 * and the process would sit there until the timeout instead of failing immediately.
	 */
	private static final class Pipe implements Runnable {

		private final InputStream stream;
		private final int cap;
		private final Process killOnOverflow;
		private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

		private volatile boolean truncated;
		private volatile int length;

		Pipe(InputStream stream, int cap, Process killOnOverflow) {
			this.stream = stream;
			this.cap = cap;
			this.killOnOverflow = killOnOverflow;
		}

		@Override
		public void run() {
			byte[] chunk = new byte[8 * 1024];
			try (InputStream in = stream) {
				int read;
				while ((read = in.read(chunk)) >= 0) {
					if (length + read > cap) {
						int remaining = Math.max(0, cap - length);
						buffer.write(chunk, 0, remaining);
						length += remaining;
						truncated = true;
						if (killOnOverflow != null) {
							killOnOverflow.destroyForcibly();
						}
						return;
					}
					buffer.write(chunk, 0, read);
					length += read;
				}
			}
			catch (IOException ex) {
				// The stream closed under us, which is what killing the process looks like
				// from here. Whatever was read already is what the caller gets.
				truncated = truncated || length >= cap;
			}
		}

		boolean truncated() {
			return truncated;
		}

		int length() {
			return length;
		}

		String text() {
			return buffer.toString(StandardCharsets.UTF_8);
		}
	}
}
