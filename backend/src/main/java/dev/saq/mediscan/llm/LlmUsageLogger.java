package dev.saq.mediscan.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records what each model call cost.
 *
 * <p>Separate from the gateway so there is one place that decides what a usage line contains -
 * and, more to the point, what it does not. Every field here is a count, an id or a duration
 * ({@code LLD} 16). No prompt, no response, no report id correlation beyond what the MDC
 * already carries.
 *
 * <p>Failures are logged too, with their outcome. A provider that fails every call still
 * spends the daily budget, so a log showing only successes would make a bad day look like a
 * quiet one.
 */
@Component
public class LlmUsageLogger {

	private static final Logger log = LoggerFactory.getLogger(LlmUsageLogger.class);

	/** A successful attempt. */
	public void recordSuccess(LlmPurpose purpose, String provider, String model,
			String promptVersion, int attempt, LlmResult<?> result) {

		log.info("llm call purpose={} provider={} model={} prompt={} attempt={} "
				+ "inTokens={} outTokens={} ms={} outcome=ok",
				purpose.label(), provider, model, promptVersion, attempt,
				result.inputTokens(), result.outputTokens(), result.latencyMs());
	}

	/**
	 * A failed attempt.
	 *
	 * <p>The exception's class, never its message. A provider error body can quote the prompt
	 * back, and the prompt is the masked report ({@code backend/CLAUDE.md}, "Exception
	 * logging").
	 */
	public void recordFailure(LlmPurpose purpose, String provider, String model,
			String promptVersion, int attempt, long latencyMs, Throwable failure) {

		log.warn("llm call purpose={} provider={} model={} prompt={} attempt={} ms={} "
				+ "outcome=failed error={}",
				purpose.label(), provider, model, promptVersion, attempt, latencyMs,
				failure.getClass().getSimpleName());
	}

	/** An attempt that never happened because the daily cap was reached. */
	public void recordCapReached(LlmPurpose purpose, int used, int cap) {
		log.warn("llm call purpose={} outcome=capped used={} cap={}", purpose.label(), used, cap);
	}
}
