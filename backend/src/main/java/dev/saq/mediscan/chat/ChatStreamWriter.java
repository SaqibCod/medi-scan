package dev.saq.mediscan.chat;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Writes the SSE events the contract defines (4.4).
 *
 * <p>One writer per answer. It owns the event names and shapes so no caller can invent one,
 * and it tracks whether the stream has ended so a late failure cannot append an event after
 * {@code done} - the contract says {@code error} <em>replaces</em> {@code sources} and
 * {@code done}, and a client that has seen {@code done} has already stopped listening.
 *
 * <p><strong>A failed write is how this application learns a reader has gone.</strong> The
 * Servlet API raises no event when a client disconnects; the write simply fails. Spring's
 * documentation is explicit that on an {@code IOException} the application must <em>not</em>
 * call {@code complete} or {@code completeWithError} - the container fires its own error
 * notification and Spring MVC completes the request. So every method here reports failure by
 * returning false and leaves the emitter alone.
 *
 * <p>JSON is assembled by hand rather than through a mapper. Each payload is two or three
 * fields with known shapes, and the one field that can contain arbitrary text - the answer -
 * is escaped explicitly below. That keeps the wire format visible in the same file as the
 * contract reference describing it.
 */
public class ChatStreamWriter {

	private final SseEmitter emitter;
	private final AtomicBoolean ended = new AtomicBoolean();

	public ChatStreamWriter(SseEmitter emitter) {
		this.emitter = emitter;
	}

	/** Always first. {@code cached} tells the client the answer cost nothing. */
	public boolean meta(UUID answerId, boolean cached) {
		return send("meta", "{\"answerId\":\"" + answerId + "\",\"cached\":" + cached + "}");
	}

	/** A piece of the answer. The client appends these in order. */
	public boolean token(String text) {
		return send("token", "{\"text\":" + quote(text) + "}");
	}

	/** The pages the answer drew on. Exactly one, just before {@code done}. */
	public boolean sources(boolean usedReport, List<ChatCache.SourcePage> pages) {
		StringBuilder json = new StringBuilder("{\"report\":").append(usedReport)
				.append(",\"pages\":[");
		for (int i = 0; i < pages.size(); i++) {
			ChatCache.SourcePage page = pages.get(i);
			if (i > 0) {
				json.append(',');
			}
			json.append("{\"slug\":").append(quote(page.slug()))
					.append(",\"title\":").append(quote(page.title()))
					.append(",\"heading\":").append(quote(page.heading())).append('}');
		}
		return send("sources", json.append("]}").toString());
	}

	/** Ends the stream normally. Nothing may follow. */
	public boolean done(String finishReason) {
		boolean sent = send("done", "{\"finishReason\":" + quote(finishReason) + "}");
		ended.set(true);
		return sent;
	}

	/**
	 * Ends the stream with a failure. Replaces {@code sources} and {@code done}.
	 *
	 * <p>The message is a fixed string chosen by the caller, never a provider error body - one
	 * of those can quote the prompt back, and the prompt contains the report.
	 */
	public boolean error(String code, String message) {
		if (ended.get()) {
			// The contract has no event after done, and a client that has seen it is no
			// longer reading. Dropping this is more honest than sending something unspecified.
			return false;
		}
		boolean sent = send("error", "{\"code\":" + quote(code) + ",\"message\":" + quote(message) + "}");
		ended.set(true);
		return sent;
	}

	/**
	 * A comment-only line, which clients ignore.
	 *
	 * <p>Two jobs: it stops a proxy closing a connection that has been quiet while the model
	 * thinks, and it is the only way to find out that a reader has closed the tab - the write
	 * fails, and that is the signal to stop paying for the answer.
	 */
	public boolean ping() {
		if (ended.get()) {
			return false;
		}
		try {
			emitter.send(SseEmitter.event().comment("ping"));
			return true;
		}
		catch (IOException | IllegalStateException ex) {
			return false;
		}
	}

	/** Whether {@code done} or {@code error} has been sent. */
	public boolean hasEnded() {
		return ended.get();
	}

	/**
	 * @return false when the write failed, which means the reader has gone
	 */
	private boolean send(String event, String data) {
		try {
			emitter.send(SseEmitter.event().name(event).data(data));
			return true;
		}
		catch (IOException | IllegalStateException ex) {
			// Deliberately not completing the emitter: the container does that itself after an
			// IOException, and calling complete here would race with it. IllegalStateException
			// means it is already complete, which is the same outcome.
			return false;
		}
	}

	/** JSON string escaping for the fields that can hold arbitrary text. */
	static String quote(String value) {
		StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> quoted.append("\\\"");
				case '\\' -> quoted.append("\\\\");
				case '\n' -> quoted.append("\\n");
				case '\r' -> quoted.append("\\r");
				case '\t' -> quoted.append("\\t");
				default -> {
					if (c < 0x20) {
						quoted.append(String.format("\\u%04x", (int) c));
					}
					else {
						quoted.append(c);
					}
				}
			}
		}
		return quoted.append('"').toString();
	}
}
