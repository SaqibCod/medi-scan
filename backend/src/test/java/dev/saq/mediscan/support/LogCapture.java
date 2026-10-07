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
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Level previousLevel;

	private LogCapture(Logger logger) {
		this.logger = logger;
		this.previousLevel = logger.getLevel();

		appender.start();
		logger.addAppender(appender);
		// TRACE, deliberately: a leak at any level is a leak.
		logger.setLevel(Level.TRACE);
	}

	/** Starts capturing. Use in a try-with-resources so the appender is always removed. */
	public static LogCapture start() {
		return new LogCapture((Logger) LoggerFactory.getLogger(APPLICATION_LOGGER));
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
		logger.setLevel(previousLevel);
	}
}
