package dev.saq.mediscan.chat;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The chat request body (contract 4.4).
 *
 * <p>The limits are the contract's, enforced here so a bad body is {@code 400 VALIDATION_ERROR}
 * before any work happens - before the owner lookup, before the rate limit, and certainly
 * before anything reaches the model.
 *
 * <p>The history cap is not only a prompt-size guard. The server stores nothing, so the client
 * supplies the conversation, which means a caller controls how much text arrives; six turns of
 * two thousand characters is the ceiling on what one request can put in front of the model.
 *
 * @param question 1 to 500 characters after trimming
 * @param history up to 6 turns, oldest first. Null and absent both mean none
 */
public record ChatRequest(
		@NotBlank(message = "question is required")
		@Size(max = 500, message = "question must be at most 500 characters")
		String question,

		@Size(max = 6, message = "history must have at most 6 turns")
		List<@Valid Turn> history) {

	public ChatRequest {
		history = history == null ? List.of() : List.copyOf(history);
	}

	/**
	 * One turn, as the client sends it.
	 *
	 * <p>Separate from {@link ChatTurn}: this one is the wire shape with its validation
	 * annotations, and that one is what the rest of the package passes around after masking.
	 * Keeping them apart means an unmasked turn has a different type from a masked one, so the
	 * two cannot be confused at a call site.
	 */
	public record Turn(
			@Pattern(regexp = "user|assistant", message = "role must be 'user' or 'assistant'")
			String role,

			@NotBlank(message = "content is required")
			@Size(max = 2000, message = "content must be at most 2000 characters")
			String content) {

		/** Hides the content. */
		@Override
		public String toString() {
			return "Turn[role=" + role + ", chars=" + (content == null ? 0 : content.length()) + "]";
		}
	}

	/** The question with surrounding whitespace removed, which is what the limits apply to. */
	public String trimmedQuestion() {
		return question == null ? "" : question.strip();
	}

	/**
	 * Hides the question and the history.
	 *
	 * <p>{@code CLAUDE.md} rule 19. This object is bound by Spring, validated, and passed into
	 * the service, so it has more opportunities than most to appear in a log line or a
	 * validation error message.
	 */
	@Override
	public String toString() {
		return "ChatRequest[questionChars=" + (question == null ? 0 : question.length())
				+ ", historyTurns=" + history.size() + "]";
	}
}
