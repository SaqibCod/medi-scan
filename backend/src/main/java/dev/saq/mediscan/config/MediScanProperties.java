package dev.saq.mediscan.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * The application's own configuration, bound from {@code mediscan.*}.
 *
 * <p>Values come from environment variables through {@code application.yml}; nothing here has
 * a secret in it except {@link Llm#apiKey()}, which is why that one is excluded from
 * {@link Llm#toString()}. Typed as records so a missing or malformed value fails at startup
 * rather than at the first request.
 *
 * <p>One prefix for the whole application rather than separate top-level {@code upload.*},
 * {@code jobs.*} and {@code llm.*} roots: every value is wired from the environment in one
 * block of {@code application.yml}, and a single prefix keeps that mapping in one place
 * instead of scattering it across unrelated namespaces.
 *
 * <p>Limits and thresholds live here rather than as literals in code, per
 * {@code backend/CLAUDE.md} ("Magic numbers").
 *
 * @param allowedOrigin the single permitted CORS origin, from {@code ALLOWED_ORIGIN}
 */
@ConfigurationProperties("mediscan")
@Validated
public record MediScanProperties(
		String allowedOrigin,
		@Valid @NotNull Session session,
		@Valid @NotNull RateLimit ratelimit,
		@Valid @NotNull Upload upload,
		@Valid @NotNull Jobs jobs,
		@Valid @NotNull Extract extract,
		@Valid @NotNull Llm llm,
		@Valid @NotNull Mask mask,
		@Valid @NotNull Retention retention) {

	/**
	 * @param ttlHours how long a guest session, and the reports it owns, live for
	 */
	public record Session(@Positive int ttlHours) {
	}

	/**
	 * @param sessionsPerHour session creations allowed per IP per hour (contract section 1.6)
	 * @param uploadsPerHour report creations allowed per IP per hour (contract section 1.6)
	 */
	public record RateLimit(@Positive int sessionsPerHour, @Positive int uploadsPerHour) {
	}

	/**
	 * @param maxBytes largest accepted upload; over this is {@code 413 FILE_TOO_LARGE}. Keep
	 *     Spring's {@code spring.servlet.multipart} limits in step with it
	 * @param maxTextChars longest accepted pasted text, and the cap on extracted text; over
	 *     this is {@code 400 VALIDATION_ERROR} at upload or {@code DOCUMENT_TOO_LONG} in the job
	 * @param minTextChars shortest accepted pasted text
	 * @param tempDir where upload temp files are written, from {@code UPLOAD_TEMP_DIR}; blank
	 *     means a {@code medi-scan-uploads} directory under the system temp directory
	 * @param tempFileMaxAge how long a temp file may survive before the retention sweep
	 *     treats it as abandoned and deletes it
	 */
	public record Upload(
			@Positive long maxBytes,
			@Positive int maxTextChars,
			@Positive int minTextChars,
			String tempDir,
			@NotNull Duration tempFileMaxAge) {
	}

	/**
	 * The report job pool. Small and bounded on purpose: the production box has 2 GB of RAM,
	 * and a queue that grows without limit would turn a traffic spike into an out-of-memory
	 * kill instead of an honest {@code 429 BUSY}.
	 *
	 * @param workers worker threads
	 * @param queueCapacity jobs that may wait before an upload is rejected with {@code BUSY}
	 * @param shutdownGrace how long to wait for running jobs on shutdown; anything still
	 *     running becomes {@code INTERRUPTED} on the next start
	 */
	public record Jobs(
			@Positive int workers,
			@Positive int queueCapacity,
			@NotNull Duration shutdownGrace) {
	}

	/**
	 * @param minCharsPerPage below this, a PDF page counts as low-text (scanned)
	 * @param minTotalChars below this across the document, the report is {@code UNREADABLE}
	 * @param maxPages more pages than this is {@code DOCUMENT_TOO_LONG}
	 */
	public record Extract(
			@Positive int minCharsPerPage,
			@Positive int minTotalChars,
			@Positive int maxPages) {
	}

	/**
	 * @param provider {@code gemini} or {@code fake}; {@code fake} is rejected outside the
	 *     {@code local} and {@code test} profiles
	 * @param apiKey the Google AI Studio API key, from {@code GEMINI_API_KEY}. Startup fails
	 *     if this is blank while {@code provider=gemini}
	 * @param model the chat model, from {@code GEMINI_CHAT_MODEL}
	 * @param timeout per-attempt timeout
	 * @param maxInvalidRetries retries after output that could not be parsed
	 * @param maxTransientRetries retries after a 429, a 5xx, or a timeout
	 * @param dailyCap attempts allowed per UTC day across all users, from
	 *     {@code GLOBAL_DAILY_LLM_CALLS}
	 * @param maxRows model rows kept from one extraction; extra rows are dropped
	 * @param maxSummaryChars the summary is truncated to this
	 * @param maxHighlights highlights kept
	 */
	public record Llm(
			@NotNull String provider,
			String apiKey,
			String model,
			@NotNull Duration timeout,
			@Min(0) int maxInvalidRetries,
			@Min(0) int maxTransientRetries,
			@Positive int dailyCap,
			@Positive int maxRows,
			@Positive int maxSummaryChars,
			@Positive int maxHighlights) {

		/** Never prints {@link #apiKey()}. A properties record's {@code toString} reaches logs. */
		@Override
		public String toString() {
			return "Llm[provider=" + provider + ", model=" + model + ", dailyCap=" + dailyCap + "]";
		}
	}

	/**
	 * @param opennlpEnabled whether the OpenNLP person-name rule runs. Off by default: the
	 *     only available English person model is a 5 MB legacy artefact trained on news text,
	 *     and the label-based rules are the defence Medi-Scan actually relies on. See
	 *     {@code SECURITY.md}
	 * @param opennlpModel classpath location of the person-name model
	 * @param opennlpMinProbability lowest span probability accepted as a name
	 * @param maxIntegrityPasses attempts to repair a masking result that failed the value
	 *     integrity check before the job gives up
	 */
	public record Mask(
			boolean opennlpEnabled,
			String opennlpModel,
			double opennlpMinProbability,
			@Positive int maxIntegrityPasses) {
	}

	/**
	 * @param intervalMs how often the cleanup job runs
	 * @param statsDays how long aggregated stats rows are kept
	 */
	public record Retention(@Positive long intervalMs, @Positive int statsDays) {
	}
}
