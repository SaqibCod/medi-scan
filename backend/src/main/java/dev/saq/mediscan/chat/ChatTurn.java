package dev.saq.mediscan.chat;

/**
 * One turn of the conversation the client sent back (contract 4.4).
 *
 * <p>The server stores no chat history, so every turn arrives from the browser with the
 * question. That is what keeps retention simple and keeps the promise that nothing typed is
 * kept - and it is also why a turn is <strong>data, never truth</strong>: a caller can forge an
 * {@code assistant} turn saying whatever they like. It goes into {@code <history>} as context
 * and is never treated as something this server said.
 *
 * @param role {@code user} or {@code assistant}
 * @param content the turn's text, 1 to 2,000 characters, masked before it reaches the model
 */
public record ChatTurn(String role, String content) {

	/**
	 * Hides the content.
	 *
	 * <p>{@code CLAUDE.md} rule 19: never log a chat question, answer or history. A record
	 * prints its fields by default, and a list of these is on the request object.
	 */
	@Override
	public String toString() {
		return "ChatTurn[role=" + role + ", chars=" + (content == null ? 0 : content.length()) + "]";
	}
}
