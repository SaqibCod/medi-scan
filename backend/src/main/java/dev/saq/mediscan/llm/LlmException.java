package dev.saq.mediscan.llm;

/**
 * The three ways a model call can fail, as separate types because the gateway treats them
 * differently.
 *
 * <p>Retrying is only correct when the failure might not recur. Splitting the cases at the
 * provider boundary means the retry policy is a {@code catch} block rather than a tangle of
 * status-code checks in the gateway, and a new provider has to decide which bucket its errors
 * belong in.
 *
 * <p>No message from any of these ever reaches a log line with content in it: providers
 * construct them with a fixed description, never with the response body, which can quote the
 * report back ({@code backend/CLAUDE.md}, "Exceptions").
 */
public abstract class LlmException extends RuntimeException {

	private LlmException(String message, Throwable cause) {
		// Stack trace suppressed: these are expected outcomes handled by the retry loop, not
		// bugs, and a provider outage would otherwise fill the log with identical traces.
		super(message, cause, false, false);
	}

	/**
	 * The response could not be parsed into the requested type.
	 *
	 * <p>Retried once, because a model that returned prose or truncated JSON often succeeds on
	 * a second attempt. Ends as {@code EXTRACTION_FAILED}.
	 */
	public static class InvalidLlmOutputException extends LlmException {

		public InvalidLlmOutputException(String message) {
			super(message, null);
		}

		public InvalidLlmOutputException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * A failure that may not recur: HTTP 429, a 5xx, a timeout, a dropped connection.
	 *
	 * <p>Retried with backoff. Ends as {@code LLM_UNAVAILABLE}.
	 */
	public static class TransientLlmException extends LlmException {

		public TransientLlmException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * A failure that will recur: a bad API key, a malformed request, a withdrawn model.
	 *
	 * <p>Never retried - repeating it would spend the daily budget discovering the same thing
	 * several times. Ends as {@code LLM_UNAVAILABLE}.
	 */
	public static class PermanentLlmException extends LlmException {

		public PermanentLlmException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
