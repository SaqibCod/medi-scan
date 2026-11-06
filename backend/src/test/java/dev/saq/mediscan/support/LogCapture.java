package dev.saq.mediscan.support;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Captures everything the application logs, at every level.
 *
 * <p>Exists for the canary test. {@code CLAUDE.md} rule 3 says report content never reaches a
 * log line, and the only way to hold that is to read the log back - including the levels a
 * test run would normally discard, because a {@code DEBUG} line on a developer's machine is
 * just as much a leak as an {@code INFO} one in production.
 *
 * <p>Attaches to the application's own logger rather than the root, so Testcontainers and
 * Hibernate noise does not dilute the assertion.
 */
public final class LogCapture implements AutoCloseable {

	private static final String APPLICATION_LOGGER = "dev.saq.mediscan";

	private final Logger logger;
	private final Logger raised;
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Level previousLevel;

	private LogCapture(Logger logger) {
		this(logger, logger);
	}

	/**
	 * @param logger where the appender is attached, so its own and its children's events are
	 *     captured at whatever level each is configured for
	 * @param raised the logger whose level is forced to {@code TRACE}
	 */
	private LogCapture(Logger logger, Logger raised) {
		this.logger = logger;
		this.raised = raised;
		this.previousLevel = raised.getLevel();

		appender.start();
		logger.addAppender(appender);
		// TRACE, deliberately: a leak at any level from our own code is a leak.
		raised.setLevel(Level.TRACE);
	}

	/** Starts capturing. Use in a try-with-resources so the appender is always removed. */
	public static LogCapture start() {
		return new LogCapture((Logger) LoggerFactory.getLogger(APPLICATION_LOGGER));
	}

	/**
	 * Captures the application at {@code TRACE} and the frameworks at their configured levels.
	 *
	 * <p>The asymmetry is deliberate, and it is what LLD 12 actually asks for: our own code
	 * must not leak a secret <em>at any level</em>, because a {@code DEBUG} line on a
	 * developer's machine is as much a leak as an {@code INFO} one in production. The
	 * frameworks are read back at the levels a deployment really runs, because that is the
	 * promise being made about them.
	 *
	 * <p>Forcing the frameworks to {@code TRACE} as well would fail for reasons nobody can
	 * fix: Spring's {@code StatementCreatorUtils}, the PostgreSQL driver's wire log and
	 * Hibernate's flush-time entity listing all print bound parameter values by design, so at
	 * {@code TRACE} they print every column - including {@code google_sub} and
	 * {@code display_name}. That is a reason never to enable {@code TRACE} on those loggers in
	 * production, recorded in {@code SECURITY.md}, not a defect this test can assert away.
	 */
	public static LogCapture startForAll() {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		Logger application = (Logger) LoggerFactory.getLogger(APPLICATION_LOGGER);

		// The appender goes on root, so framework events reach it too; only the application
		// logger's level is raised. Application events propagate up to root's appender.
		return new LogCapture(root, application);
	}

	/**
	 * Every captured line, formatted as it would appear.
	 *
	 * <p>Includes the formatted message, the arguments, and every exception message in the
	 * cause chain - because an exception message is the likeliest accidental route for report
	 * content into a log.
	 */
	public String allText() {
		StringBuilder text = new StringBuilder();
		for (ILoggingEvent event : List.copyOf(appender.list)) {
			text.append(event.getFormattedMessage()).append('\n');

			var throwable = event.getThrowableProxy();
			while (throwable != null) {
				text.append(throwable.getClassName()).append(": ")
						.append(throwable.getMessage()).append('\n');
				throwable = throwable.getCause();
			}
		}
		return text.toString();
	}

	public int lineCount() {
		return appender.list.size();
	}

	@Override
	public void close() {
		logger.detachAppender(appender);
		appender.stop();
		raised.setLevel(previousLevel);
	}
}
