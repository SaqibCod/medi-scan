package dev.saq.mediscan.llm;

/**
 * A successful model call: the parsed value, plus what it cost.
 *
 * <p>Token counts and latency are carried back rather than logged inside the provider, so the
 * gateway records usage in one place and the provider stays a thin adapter.
 *
 * @param value the response, already parsed into the requested type
 * @param inputTokens prompt tokens, or 0 when the provider did not report them
 * @param outputTokens completion tokens, or 0 when not reported
 * @param latencyMs wall-clock time for the attempt
 */
public record LlmResult<T>(T value, int inputTokens, int outputTokens, long latencyMs) {

	public int totalTokens() {
		return inputTokens + outputTokens;
	}

	/**
	 * Hides {@link #value()}.
	 *
	 * <p>The value is the extracted biomarker list or the patient summary - report content
	 * either way.
	 */
	@Override
	public String toString() {
		return "LlmResult[inputTokens=" + inputTokens + ", outputTokens=" + outputTokens
				+ ", latencyMs=" + latencyMs + "]";
	}
}
