package dev.saq.mediscan.llm;

/**
 * One model backend.
 *
 * <p>The seam that keeps Spring AI out of the rest of the application. Only implementations of
 * this interface import Spring AI classes; {@code analysis} talks to {@link LlmGateway}, which
 * talks to this ({@code CLAUDE.md}, "LLM access"). That is what makes the fake provider a
 * complete substitute rather than a mock with gaps, and what will make adding OpenAI later a
 * new class rather than an edit to the pipeline.
 *
 * <p>Implementations are responsible for exactly two things: producing a parsed value of the
 * requested type, and classifying their failures into the three {@link LlmException} kinds.
 * Retries, the daily cap, usage logging and error mapping all belong to the gateway, so a
 * provider cannot get the budget accounting wrong.
 */
public interface LlmProvider {

	/** Stable id for logs and the usage record: {@code gemini}, {@code fake}. */
	String id();

	/** The model actually in use, for the usage record. */
	String model();

	/**
	 * The model used for {@code purpose}.
	 *
	 * <p>Chat is configured separately from the pipeline ({@code mediscan.chat.model} rather
	 * than {@code mediscan.llm.model}), because the two are chosen on different grounds and
	 * because the committed Phase 3 baseline names the model extraction ran against - moving
	 * one variable should not move both. Without this method the usage log would report the
	 * pipeline's model for every chat call, which is exactly the kind of quietly wrong log
	 * line that cost two days in Phase 3.
	 */
	default String model(LlmPurpose purpose) {
		return model();
	}

	/**
	 * Calls the model and parses the response into {@code outputType}.
	 *
	 * @throws LlmException.InvalidLlmOutputException when the response is not parseable
	 * @throws LlmException.TransientLlmException on a failure that may not recur
	 * @throws LlmException.PermanentLlmException on a failure that will recur
	 */
	<T> LlmResult<T> structured(LlmRequest request, Class<T> outputType);

	/**
	 * Streams an answer, for chat (phase 5).
	 *
	 * <p>Cold: nothing is sent until the returned {@link Flux} is subscribed to, and cancelling
	 * that subscription must stop the underlying HTTP call. Both matter for quota - a stream
	 * nobody is reading still costs tokens - and cancellation is how the number guard stops an
	 * answer it has decided not to send.
	 *
	 * <p>Failures arrive as {@code onError} with the same three {@link LlmException} kinds
	 * {@link #structured} throws, so the gateway maps them in one place.
	 */
	reactor.core.publisher.Flux<LlmStreamChunk> stream(LlmRequest request);
}
