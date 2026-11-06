package dev.saq.mediscan.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.llm.DailyCapGuard;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.llm.LlmUsageLogger;

/**
 * The only way to turn text into a vector.
 *
 * <p>{@link dev.saq.mediscan.llm.LlmGateway}'s counterpart for embeddings, and it exists for
 * the same reason: everything that must happen on every call happens here, so no caller can
 * skip it. The daily cap, the retry policy and the usage log are all this class's business
 * ({@code CLAUDE.md} rule 8 - <em>every</em> LLM call goes through the cap, and an embedding
 * call is one).
 *
 * <p>This is also the reason {@code ContentChunkRepository} is hand-written rather than Spring
 * AI's {@code PgVectorStore}: that store embeds inside its own {@code add()} and
 * {@code similaritySearch()}, which would put embedding calls somewhere this class cannot see
 * them.
 *
 * <p><strong>Task type is set per call.</strong> A document being indexed and a question being
 * asked are embedded for different jobs, and the API models that: {@code RETRIEVAL_DOCUMENT}
 * optimises a passage to be found, {@code RETRIEVAL_QUERY} optimises a question to find one.
 * Using one type for both measurably costs recall, and it is one argument.
 *
 * <p><strong>Batching is what makes a full re-index affordable.</strong> One request is one
 * call against the cap however many texts it carries, so 80 chunks cost 5 calls rather than
 * 80.
 */
@Component
public class EmbeddingGateway {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingGateway.class);

	/** Waits before transient retries. Index 0 is the wait before the second attempt. */
	private static final List<Duration> BACKOFF =
			List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));

	private final EmbeddingModel embeddingModel;
	private final DailyCapGuard capGuard;
	private final LlmUsageLogger usageLogger;
	private final String model;
	private final int dimensions;
	private final int batchSize;
	private final int maxTransientRetries;

	public EmbeddingGateway(EmbeddingModel embeddingModel, DailyCapGuard capGuard,
			LlmUsageLogger usageLogger, MediScanProperties properties) {

		this.embeddingModel = embeddingModel;
		this.capGuard = capGuard;
		this.usageLogger = usageLogger;
		this.model = properties.embedding().model();
		this.dimensions = properties.embedding().dimensions();
		this.batchSize = properties.embedding().batchSize();
		this.maxTransientRetries = properties.embedding().maxTransientRetries();

		log.info("Embedding gateway using model={} dimensions={} batchSize={}",
				model, dimensions, batchSize);
	}

	/** The configured vector width, which must match the {@code vector_store} column. */
	public int dimensions() {
		return dimensions;
	}

	/** The configured model id, stored in {@code content_index} beside each page's hash. */
	public String model() {
		return model;
	}

	/**
	 * Embeds page chunks for indexing.
	 *
	 * @return one vector per text, in the same order
	 * @throws EmbeddingUnavailableException when the cap is reached or the provider keeps
	 *     failing. Indexing catches this and leaves the existing index alone
	 */
	public List<float[]> embedDocuments(List<String> texts) {
		List<float[]> vectors = new ArrayList<>(texts.size());
		// Batched, because one request is one call against the cap however many texts it
		// carries. A 21-page re-index is a handful of calls rather than eighty.
		for (int from = 0; from < texts.size(); from += batchSize) {
			int to = Math.min(from + batchSize, texts.size());
			vectors.addAll(call(texts.subList(from, to),
					GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT));
		}
		return List.copyOf(vectors);
	}

	/**
	 * Embeds one question for retrieval.
	 *
	 * <p>Always the masked question, never the raw one: by the time anything reaches this class
	 * the typed text has been through {@code TypedTextMasker}.
	 */
	public float[] embedQuery(String maskedQuestion) {
		return call(List.of(maskedQuestion),
				GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY).getFirst();
	}

	/**
	 * One request, with the cap taken for every attempt.
	 *
	 * <p>Every attempt acquires, retries included, for the same reason the chat gateway does: a
	 * retry costs the provider the same as a first try, so counting only successes would let a
	 * failing provider spend the budget several times over.
	 */
	private List<float[]> call(List<String> texts,
			GoogleGenAiTextEmbeddingOptions.TaskType taskType) {

		GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
				// Set per request rather than left to the bean's defaults. Phase 3 lost two
				// days to exactly that: the autoconfigured model silently fell back to a
				// library default while the logs reported the configured one. Setting it here
				// makes mediscan.embedding.model the single source of truth, so the model
				// recorded in content_index is true by construction.
				.model(model)
				// Truncation to 768 is requested, not assumed. The column is 768 wide and an
				// insert of any other width fails - better to ask for the right size than to
				// discover the mismatch one page into a re-index.
				.dimensions(dimensions)
				.taskType(taskType)
				.build();

		int transientRetries = 0;
		int attempt = 0;

		while (true) {
			attempt++;

			if (!capGuard.tryAcquire()) {
				usageLogger.recordCapReached(LlmPurpose.EMBED, capGuard.usedToday(), capGuard.cap());
				throw new EmbeddingUnavailableException("daily cap reached");
			}

			long startedAt = System.nanoTime();
			try {
				EmbeddingResponse response =
						embeddingModel.call(new EmbeddingRequest(texts, options));

				List<float[]> vectors = response.getResults().stream()
						.map(embedding -> embedding.getOutput())
						.toList();

				if (vectors.size() != texts.size()) {
					// gemini-embedding-2 returns a single aggregated vector for several inputs
					// instead of one each, which would silently pair chunk 0's text with the
					// whole batch's meaning. Caught here rather than trusted.
					throw new EmbeddingUnavailableException(
							"provider returned " + vectors.size() + " vectors for "
									+ texts.size() + " texts");
				}
				for (float[] vector : vectors) {
					if (vector.length != dimensions) {
						throw new EmbeddingUnavailableException(
								"provider returned a vector of width " + vector.length
										+ ", expected " + dimensions);
					}
				}

				log.debug("embedding call purpose=embed model={} taskType={} texts={} ms={}",
						model, taskType, texts.size(),
						(System.nanoTime() - startedAt) / 1_000_000);
				return vectors;
			}
			catch (EmbeddingUnavailableException ex) {
				throw ex;
			}
			catch (RuntimeException ex) {
				long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
				// The class name only. A provider error body can quote the request back, and
				// for a query the request is what someone typed (CLAUDE.md rule 19).
				usageLogger.recordFailure(LlmPurpose.EMBED, "gemini", model, "none", attempt,
						latencyMs, ex);

				if (transientRetries < maxTransientRetries) {
					sleep(BACKOFF.get(Math.min(transientRetries, BACKOFF.size() - 1)));
					transientRetries++;
					continue;
				}
				throw new EmbeddingUnavailableException(
						"embedding failed after " + attempt + " attempts: "
								+ ex.getClass().getSimpleName());
			}
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new EmbeddingUnavailableException("interrupted while retrying");
		}
	}

	/**
	 * No vector could be produced.
	 *
	 * <p>Carries a fixed description, never the text that was being embedded and never a
	 * provider error body - a query is something a person typed.
	 */
	public static class EmbeddingUnavailableException extends RuntimeException {

		public EmbeddingUnavailableException(String message) {
			super(message);
		}
	}
}
