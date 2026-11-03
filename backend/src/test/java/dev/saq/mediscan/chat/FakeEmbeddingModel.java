package dev.saq.mediscan.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A deterministic {@link EmbeddingModel} for tests and local development.
 *
 * <p>No test reaches a real embedding API ({@code backend/CLAUDE.md}, "Testing"). This stands
 * in completely rather than returning noise, because a retrieval test against random vectors
 * proves only that a query ran.
 *
 * <p><strong>It is a real, if crude, embedding: hashed bag of words, L2-normalised.</strong>
 * Each lowercased word is hashed to one of the 768 dimensions and adds to it, then the vector
 * is scaled to unit length. Two texts sharing words land close together under cosine
 * similarity and two that share none land near zero - so "what does HDL measure" genuinely
 * retrieves the HDL chunk, and the similarity threshold genuinely filters. It has no idea that
 * "cholesterol" and "lipid" are related, which is exactly the difference between this and the
 * real model, and the reason the chat evaluation runs against the real one.
 *
 * <p>Normalising matters for a second reason: the production vectors are compared with cosine
 * distance, so a fake that returned unnormalised vectors would let a test pass on a similarity
 * scale that does not exist in production.
 *
 * <p>Only active when {@code mediscan.llm.provider=fake}, the same switch that selects
 * {@code FakeLlmProvider} - one switch, so the two cannot disagree about whether this is a
 * test run.
 */
@Component
@ConditionalOnProperty(name = "mediscan.llm.provider", havingValue = "fake")
public class FakeEmbeddingModel implements EmbeddingModel {

	/** Must match {@code mediscan.embedding.dimensions} and the {@code vector_store} column. */
	private static final int DIMENSIONS = 768;

	private final AtomicInteger callCount = new AtomicInteger();
	private final AtomicInteger textCount = new AtomicInteger();

	private volatile RuntimeException failure;

	// --- scripting ----------------------------------------------------------

	/** Makes every following call throw, for the "indexing must not fail startup" test. */
	public void failWith(RuntimeException exception) {
		this.failure = exception;
	}

	/** Stops failing, and clears the counters. */
	public void reset() {
		failure = null;
		callCount.set(0);
		textCount.set(0);
	}

	/**
	 * Requests made, not texts embedded.
	 *
	 * <p>This is the number that matters: one request is one call against the daily cap
	 * however many texts it carries, so this is what the batching and "a restart embeds
	 * nothing" tests assert on.
	 */
	public int callCount() {
		return callCount.get();
	}

	/** Texts embedded across all calls, for the batching test. */
	public int textCount() {
		return textCount.get();
	}

	// --- model --------------------------------------------------------------

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		callCount.incrementAndGet();
		textCount.addAndGet(request.getInstructions().size());

		RuntimeException scripted = failure;
		if (scripted != null) {
			throw scripted;
		}

		List<Embedding> results = new ArrayList<>(request.getInstructions().size());
		int index = 0;
		for (String text : request.getInstructions()) {
			results.add(new Embedding(embedText(text), index++));
		}
		return new EmbeddingResponse(results);
	}

	@Override
	public float[] embed(Document document) {
		return embedText(document.getText());
	}

	@Override
	public int dimensions() {
		return DIMENSIONS;
	}

	/** Hashed bag of words, scaled to unit length. */
	static float[] embedText(String text) {
		float[] vector = new float[DIMENSIONS];

		for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (word.isEmpty()) {
				continue;
			}
			// Math.floorMod, not %, so a negative hash does not throw on the index.
			vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
		}

		double length = 0;
		for (float component : vector) {
			length += (double) component * component;
		}
		length = Math.sqrt(length);

		if (length == 0) {
			// An empty or punctuation-only text. A zero vector would make cosine distance
			// undefined, and pgvector returns NaN for it, so one arbitrary non-zero component
			// keeps the maths defined and the similarity near zero against anything real.
			vector[0] = 1f;
			return vector;
		}
		for (int i = 0; i < vector.length; i++) {
			vector[i] /= (float) length;
		}
		return vector;
	}
}
