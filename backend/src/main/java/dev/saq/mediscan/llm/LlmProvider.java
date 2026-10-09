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
	 * Calls the model and parses the response into {@code outputType}.
	 *
	 * @throws LlmException.InvalidLlmOutputException when the response is not parseable
	 * @throws LlmException.TransientLlmException on a failure that may not recur
	 * @throws LlmException.PermanentLlmException on a failure that will recur
	 */
	<T> LlmResult<T> structured(LlmRequest request, Class<T> outputType);

	// Phase 5 adds streaming for chat: Flux<String> stream(LlmRequest request).
}
