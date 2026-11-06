package dev.saq.mediscan.llm;

/**
 * One piece of a streamed answer.
 *
 * <p>Modelled on what the provider actually sends rather than on what would be tidy. A Gemini
 * stream delivers text a few words at a time, repeats the running token counts on every chunk,
 * and puts the finish reason on the last one - verified against the live API, where a five-chunk
 * answer carried usage on all five and {@code finishReason=STOP} only on the last.
 *
 * <p>So token counts here are <strong>cumulative, not per chunk</strong>: whoever logs usage
 * reads the final chunk and ignores the rest. Treating them as increments would multiply a
 * short answer's cost by its chunk count.
 *
 * @param text the new text in this chunk, which may be empty on the final one
 * @param inputTokens prompt tokens so far, or 0 when the provider has not said yet
 * @param outputTokens answer tokens so far, or 0 when the provider has not said yet
 * @param finishReason the provider's own token ({@code STOP}, {@code MAX_TOKENS},
 *     {@code SAFETY}, ...) on the last chunk, otherwise null. A short fixed token, never
 *     response content, so it is safe to log
 */
public record LlmStreamChunk(
		String text,
		int inputTokens,
		int outputTokens,
		String finishReason) {

	/** A text-only chunk, for providers and fakes that report usage only at the end. */
	public static LlmStreamChunk text(String text) {
		return new LlmStreamChunk(text, 0, 0, null);
	}

	/** Whether this chunk carries the provider's final accounting. */
	public boolean isFinal() {
		return finishReason != null;
	}

	/**
	 * Hides the text.
	 *
	 * <p>This is a piece of an answer about someone's lab report, and
	 * {@code CLAUDE.md} rule 19 forbids logging any of it. A record prints its fields by
	 * default and these flow through the gateway, the tag parser and the guard, so this is one
	 * of the easiest types in the codebase to leak by accident.
	 */
	@Override
	public String toString() {
		return "LlmStreamChunk[chars=" + text.length() + ", inputTokens=" + inputTokens
				+ ", outputTokens=" + outputTokens + ", finishReason=" + finishReason + "]";
	}
}
