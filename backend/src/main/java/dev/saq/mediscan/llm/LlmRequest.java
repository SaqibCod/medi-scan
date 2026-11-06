package dev.saq.mediscan.llm;

/**
 * One model call.
 *
 * @param purpose which step this is, for logging and stats
 * @param systemPrompt the instructions, loaded from a versioned prompt file
 * @param userContent the data, always wrapped in delimiters by the caller
 * @param temperature 0 for both steps - transcription and summarisation of fixed input have
 *     nothing for sampling to improve
 * @param maxOutputTokens a ceiling, so a runaway response cannot spend the budget
 */
public record LlmRequest(
		LlmPurpose purpose,
		String systemPrompt,
		String userContent,
		double temperature,
		int maxOutputTokens) {

	/**
	 * Hides both prompts.
	 *
	 * <p>{@link #userContent()} is the masked report, and {@link #systemPrompt()} is long
	 * enough to bury a log file. A request object is passed through the gateway, the retry
	 * loop and the provider, so it has more chances than most to end up in a log line
	 * ({@code CLAUDE.md} rule 3: never log LLM prompts).
	 */
	@Override
	public String toString() {
		return "LlmRequest[purpose=" + purpose + ", contentChars=" + userContent.length()
				+ ", maxOutputTokens=" + maxOutputTokens + "]";
	}
}
