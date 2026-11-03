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
		@Valid @NotNull Ocr ocr,
		@Valid @NotNull Llm llm,
		@Valid @NotNull Mask mask,
		@Valid @NotNull Retention retention,
		@Valid @NotNull History history,
		@Valid @NotNull Trends trends,
		@Valid @NotNull Admin admin,
		@Valid @NotNull Chat chat,
		@Valid @NotNull Embedding embedding,
		@Valid @NotNull Rag rag) {

	/**
	 * @param ttlHours how long a guest session, and the reports it owns, live for
	 */
	public record Session(@Positive int ttlHours) {
	}

	/**
	 * @param sessionsPerHour session creations allowed per IP per hour (contract section 1.6)
	 * @param uploadsPerHour report creations allowed per caller per hour. Keyed by user id
	 *     when signed in and by client IP otherwise (contract section 1.6, LLD 8)
	 * @param authPerMinute sign-in and refresh attempts allowed per IP per minute, each
	 *     endpoint with its own bucket (LLD 9)
	 */
	public record RateLimit(@Positive int sessionsPerHour, @Positive int uploadsPerHour,
			@Positive int authPerMinute) {
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
	 * Reading photos and scanned pages with Tesseract.
	 *
	 * <p>Every limit here exists to bound something that would otherwise be unbounded on a 2 GB
	 * box: how long one page may take, how long a whole report may spend in OCR, how many pages
	 * may be OCR'd, how large an image may be, how much the child process may print, and how
	 * little confidence is still worth believing.
	 *
	 * @param enabled whether images are accepted and scanned pages are OCR'd. When false,
	 *     images get {@code 415 UNSUPPORTED_FILE_TYPE} at upload and a text-less PDF fails with
	 *     {@code UNREADABLE}, exactly as before this phase. The {@code local} profile turns
	 *     itself off with a warning when Tesseract is missing; {@code prod} refuses to start
	 * @param command the Tesseract program. A path, never a shell string: arguments are passed
	 *     as a list so nothing in a file name can be read as a command
	 * @param tessdataDir set to use a different model directory, such as one holding
	 *     {@code tessdata_best}; blank uses the system default
	 * @param psm Tesseract's page segmentation mode. 4 suits a single column of variable-sized
	 *     text, which is what a lab table is; the evaluation picks the final value
	 * @param threads value for {@code OMP_THREAD_LIMIT}. 1 leaves the JVM a CPU on a 2-vCPU box,
	 *     and measurement showed no meaningful gain from more on a page this size
	 * @param pageTimeout how long one page may take before the process is killed
	 * @param totalTimeout how long one report may spend in OCR in total, including time spent
	 *     waiting for the permit. Over this the job fails with {@code EXTRACTION_FAILED}
	 * @param maxPages scanned pages OCR'd per report; more is {@code DOCUMENT_TOO_LONG}
	 * @param maxPixels largest image accepted, as width times height. Checked against the file
	 *     header <em>before</em> anything is decoded, so a small file claiming to be enormous is
	 *     refused rather than decoded
	 * @param maxDecodedBytes largest raster the decoder may allocate. The pixel cap bounds what
	 *     is accepted; this bounds what is actually allocated, which subsampling keeps far below
	 *     the source size
	 * @param minLongEdge shortest long edge given to Tesseract; smaller images are scaled up,
	 *     because Tesseract reads small text poorly
	 * @param maxLongEdge longest long edge given to Tesseract, and the target the decoder
	 *     subsamples towards
	 * @param maxOutputBytes most the child process may write to stdout before it is killed
	 * @param minPageConfidence below this mean confidence a page counts as empty rather than
	 *     being fed to the model as garbage
	 * @param minValueConfidence below this, a value, range or date keeps its printed text but
	 *     gets {@code UNKNOWN} and no number. The evaluation picks the final value
	 */
	public record Ocr(
			boolean enabled,
			@NotNull String command,
			String tessdataDir,
			@Min(0) int psm,
			@Positive int threads,
			@NotNull Duration pageTimeout,
			@NotNull Duration totalTimeout,
			@Positive int maxPages,
			@Positive long maxPixels,
			@Positive long maxDecodedBytes,
			@Positive int minLongEdge,
			@Positive int maxLongEdge,
			@Positive int maxOutputBytes,
			@Min(0) double minPageConfidence,
			@Min(0) double minValueConfidence) {
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
	 * @param userReportDays how long a signed-in user's reports live, from
	 *     {@code USER_RETENTION_DAYS}. A guest's report expires with its session instead
	 * @param inactiveAccountDays an account with no sign-in for this long is deleted, with
	 *     everything it owns, from {@code INACTIVE_ACCOUNT_DAYS}
	 */
	public record Retention(@Positive long intervalMs, @Positive int statsDays,
			@Positive int userReportDays, @Positive int inactiveAccountDays) {
	}

	/**
	 * {@code GET /api/reports} paging (contract section 4.5).
	 *
	 * @param defaultSize page size when {@code size} is not given
	 * @param maxSize largest accepted {@code size}; over this is {@code 400 VALIDATION_ERROR}
	 */
	public record History(@Positive int defaultSize, @Positive int maxSize) {
	}

	/**
	 * {@code GET /api/trends} and {@code GET /api/trends/markers} (contract section 5).
	 *
	 * @param pointCap points kept per series; the latest this many, oldest first (LLD D2)
	 * @param markerMinReports a marker must appear in at least this many distinct reports to
	 *     be listed or charted (LLD D7)
	 * @param markerCap markers returned by the picker, at most
	 */
	public record Trends(@Positive int pointCap, @Positive int markerMinReports,
			@Positive int markerCap) {
	}

	/**
	 * {@code GET /api/admin/stats} (contract section 6).
	 *
	 * @param defaultDays range when {@code days} is not given
	 * @param maxDays largest accepted {@code days}; over this is {@code 400 VALIDATION_ERROR}
	 */
	public record Admin(@Positive int defaultDays, @Positive int maxDays) {
	}

	/**
	 * {@code POST /api/reports/{id}/chat} (contract section 4.4, LLD 11).
	 *
	 * @param model the chat model. <strong>Its own property, not {@code mediscan.llm.model}.</strong>
	 *     Those two steps are measured by the Phase 3 evaluation and its committed baseline
	 *     names the model it ran against; repointing one variable at both would move the
	 *     pipeline's model as a side effect of choosing a chat model. They are also chosen on
	 *     different criteria - extraction wants the most accurate transcriber, chat wants the
	 *     one that follows the decision-tag rule and actually answers
	 * @param maxOutputTokens ceiling on one answer, so a runaway response cannot spend the
	 *     budget. The prompt asks for 120 words, which is well inside this
	 * @param temperature low but not zero: an answer reads better than a transcription, and the
	 *     number guard - not sampling - is what keeps the figures honest
	 * @param historyTokenBudget history is trimmed to about this, oldest turns dropped first
	 * @param ratePerHour chat messages per caller per hour, keyed by user id when signed in and
	 *     by IP otherwise (contract section 1.6)
	 * @param cacheTtl how long a cached answer lives
	 * @param cacheMaxEntries entries held before the oldest are evicted
	 * @param pingInterval gap between {@code : ping} comment lines. These keep proxies from
	 *     closing an idle stream and are also the only way this app learns a client has gone:
	 *     the Servlet API raises no disconnect event, so a failed write is the signal
	 * @param streamTimeout longest one answer may take before the stream is closed
	 */
	public record Chat(
			@NotNull String model,
			@Positive int maxOutputTokens,
			double temperature,
			@Positive int historyTokenBudget,
			@Positive int ratePerHour,
			@NotNull Duration cacheTtl,
			@Positive int cacheMaxEntries,
			@NotNull Duration pingInterval,
			@NotNull Duration streamTimeout) {
	}

	/**
	 * The embedding model behind chat retrieval (LLD C2).
	 *
	 * @param model fixed once chosen. Changing it invalidates every stored vector, because
	 *     vectors from two models are not comparable, so {@code ContentIndexer} re-embeds
	 *     everything when this differs from what {@code content_index} recorded
	 * @param dimensions the vector width requested from the API. Must equal the width of
	 *     {@code vector_store.embedding}, which a migration fixed; startup fails when they
	 *     disagree rather than writing vectors the column will reject one page in
	 * @param batchSize texts sent per embedding request while indexing. One request is one call
	 *     against the daily cap, so batching is what keeps a full re-index affordable
	 * @param maxTransientRetries retries after a 429, a 5xx or a timeout
	 */
	public record Embedding(
			@NotNull String model,
			@Positive int dimensions,
			@Positive int batchSize,
			@Min(0) int maxTransientRetries) {
	}

	/**
	 * Retrieval settings (LLD 6.2, 11).
	 *
	 * @param topK chunks retrieved for one question
	 * @param minSimilarity cosine similarity a chunk must reach to be used at all. Retrieving
	 *     nothing is a valid outcome - the answer then rests on the report alone, which is
	 *     better than padding the prompt with a page about something else
	 * @param requireReviewed when true, a page whose {@code reviewedOn} is null is not indexed.
	 *     True in {@code prod} so unreviewed medical text cannot reach a live answer; false in
	 *     {@code local}, where nothing is reviewed yet and an empty index would make chat
	 *     untestable
	 * @param indexOnStartup whether to index when the application becomes ready. Off under the
	 *     test profile: embedding is a model call and comes out of the daily cap, so a startup
	 *     index would spend the budget every test class measures - and the indexing tests call
	 *     the indexer directly anyway, which is both faster and more precise
	 */
	public record Rag(
			@Positive int topK,
			double minSimilarity,
			boolean requireReviewed,
			boolean indexOnStartup) {
	}
}
