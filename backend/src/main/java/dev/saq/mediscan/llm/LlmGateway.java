package dev.saq.mediscan.llm;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;

/**
 * The only way into the {@code llm} package from the pipeline.
 *
 * <p>Everything that must happen on every model call happens here, so no caller and no
 * provider can skip it: the daily cap, the retry policy, usage logging, and the mapping from
 * provider failure to report failure code.
 *
 * <p><strong>Every attempt takes from the cap, retries included.</strong> A retry costs the
 * provider the same as a first try, so counting only successes would let a failing provider
 * spend the budget several times over. The worst case for one report is therefore
 * {@code (1 + invalidRetries + transientRetries)} calls per step - six across both steps with
 * the default settings - which is the number the cap has to be set against.
 *
 * <p><strong>Backoff is a plain sleep.</strong> This runs on a report-job worker, of which
 * there are two, and blocking one for a second is exactly the intended behaviour: it slows the
 * pipeline down while a provider is struggling instead of hammering it.
 */
@Component
public class LlmGateway {

	private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

	/** Waits before transient retries. Index 0 is the wait before the second attempt. */
	private static final List<Duration> BACKOFF =
			List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));

	private final LlmProvider provider;
	private final DailyCapGuard capGuard;
	private final LlmUsageLogger usageLogger;
	private final PromptTemplates prompts;
	private final TokenRecorder tokenRecorder;
	private final int maxInvalidRetries;
	private final int maxTransientRetries;

	/**
	 * @param tokenRecorder optional. The gateway should not care whether anything is
	 *     recording, and a no-op default keeps the usage log - which always runs - as the
	 *     thing that cannot be switched off
	 */
	public LlmGateway(LlmProvider provider, DailyCapGuard capGuard, LlmUsageLogger usageLogger,
			PromptTemplates prompts, ObjectProvider<TokenRecorder> tokenRecorder,
			MediScanProperties properties) {

		this.provider = provider;
		this.capGuard = capGuard;
		this.usageLogger = usageLogger;
		this.prompts = prompts;
		this.tokenRecorder = tokenRecorder.getIfAvailable(() -> (input, output) -> {
		});
		this.maxInvalidRetries = properties.llm().maxInvalidRetries();
		this.maxTransientRetries = properties.llm().maxTransientRetries();

		log.info("LLM gateway using provider={} model={}", provider.id(), provider.model());
	}

	/**
	 * Calls the model and returns a parsed value.
	 *
	 * @param userContent the data turn, already wrapped in delimiters by the caller
	 * @param isValid an extra validity check beyond parsing, for cases the schema cannot
	 *     express - a summary that came back structurally fine but blank. A {@code false}
	 *     result is treated exactly like unparseable output, so it is retried once
	 * @throws ReportFailure {@code CAPACITY} when the daily cap is reached,
	 *     {@code EXTRACTION_FAILED} when output stays invalid, {@code LLM_UNAVAILABLE} when
	 *     the provider stays unreachable
	 */
	public <T> T structured(LlmPurpose purpose, String userContent, Class<T> outputType,
			double temperature, int maxOutputTokens, Predicate<T> isValid) {

		LlmRequest request = new LlmRequest(purpose, prompts.systemPrompt(purpose), userContent,
				temperature, maxOutputTokens);
		String promptVersion = prompts.version(purpose);

		int invalidRetries = 0;
		int transientRetries = 0;
		int attempt = 0;

		while (true) {
			attempt++;

			if (!capGuard.tryAcquire()) {
				usageLogger.recordCapReached(purpose, capGuard.usedToday(), capGuard.cap());
				throw new ReportFailure(ReportErrorCode.CAPACITY);
			}

			long startedAt = System.nanoTime();
			try {
				LlmResult<T> result = provider.structured(request, outputType);

				if (!isValid.test(result.value())) {
					// Parsed, but unusable. Same treatment as a parse failure: the model
					// produced something the pipeline cannot work with.
					throw new LlmException.InvalidLlmOutputException(
							"response failed the caller's validity check");
				}

				usageLogger.recordSuccess(purpose, provider.id(), provider.model(), promptVersion,
						attempt, result);
				tokenRecorder.recordTokens(result.inputTokens(), result.outputTokens());
				return result.value();
			}
			catch (LlmException.InvalidLlmOutputException ex) {
				recordFailure(purpose, promptVersion, attempt, startedAt, ex);

				if (invalidRetries++ < maxInvalidRetries) {
					// No backoff: the provider is healthy, it just produced something
					// unusable, and waiting would not change that.
					continue;
				}
				throw new ReportFailure(ReportErrorCode.EXTRACTION_FAILED);
			}
			catch (LlmException.TransientLlmException ex) {
				recordFailure(purpose, promptVersion, attempt, startedAt, ex);

				if (transientRetries < maxTransientRetries) {
					sleep(BACKOFF.get(Math.min(transientRetries, BACKOFF.size() - 1)));
					transientRetries++;
					continue;
				}
				throw new ReportFailure(ReportErrorCode.LLM_UNAVAILABLE);
			}
			catch (LlmException.PermanentLlmException ex) {
				recordFailure(purpose, promptVersion, attempt, startedAt, ex);
				// Never retried: a bad key or a withdrawn model will fail identically, and
				// retrying would spend the budget learning that twice more.
				throw new ReportFailure(ReportErrorCode.LLM_UNAVAILABLE);
			}
		}
	}

	/** The common case: parsing succeeded, so the value is valid. */
	public <T> T structured(LlmPurpose purpose, String userContent, Class<T> outputType,
			double temperature, int maxOutputTokens) {

		return structured(purpose, userContent, outputType, temperature, maxOutputTokens,
				value -> true);
	}

	private void recordFailure(LlmPurpose purpose, String promptVersion, int attempt,
			long startedAt, LlmException failure) {

		long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
		usageLogger.recordFailure(purpose, provider.id(), provider.model(), promptVersion,
				attempt, latencyMs, failure);
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			// A shutdown landed mid-backoff. Stop rather than finish the report: the job is
			// about to be cut off anyway, and StartupRecovery will mark it INTERRUPTED.
			throw new ReportFailure(ReportErrorCode.INTERRUPTED);
		}
	}

	/**
	 * Where token counts go.
	 *
	 * <p>An interface declared here rather than a direct call into {@code stats}, because the
	 * gateway should not care whether anything is recording. {@code stats} supplies the real
	 * implementation; tests supply a no-op.
	 */
	public interface TokenRecorder {

		void recordTokens(int inputTokens, int outputTokens);
	}
}
