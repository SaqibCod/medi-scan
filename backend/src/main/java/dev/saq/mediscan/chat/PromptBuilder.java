package dev.saq.mediscan.chat;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Assembles the user turn for a chat answer ({@code LLD} 6.4, {@code docs/dataflow.md} 6).
 *
 * <p>Five blocks in a fixed order: the report, the validated results, the retrieved reference
 * pages, the recent turns, and the question. The order is the one the dataflow document
 * describes, and it matters - instructions come from the system prompt, which the model saw
 * first, and everything here is data arriving afterwards.
 *
 * <p><strong>Delimiters hold because the content is escaped.</strong> A block that could
 * contain a literal {@code </report>} would let a report, a page or a typed question close its
 * own tag and start issuing instructions. {@code <} and {@code >} are replaced with the
 * look-alikes {@code ‹} and {@code ›} inside every block, and the system prompt tells the model
 * to read them as angle brackets. That keeps the text legible to the model - it still sees
 * "less than 100" written as "‹100" - while making the tags unforgeable. The model has no
 * tools, so the worst an injected instruction can achieve is changing the words of one answer,
 * and that answer still passes the number guard.
 *
 * <p><strong>History is data, not truth.</strong> The client sends it, so a caller can forge an
 * {@code assistant} turn saying anything. It goes in as context and is never treated as
 * something this server said.
 *
 * <p>A plain class: no model, no database, nothing to mock, so the escaping and the budget can
 * be table-tested directly.
 */
@Component
public class PromptBuilder {

	/** Roughly four characters per token, the usual English approximation. */
	private static final int CHARS_PER_TOKEN = 4;

	private final int historyTokenBudget;

	public PromptBuilder(MediScanProperties properties) {
		this.historyTokenBudget = properties.chat().historyTokenBudget();
	}

	/**
	 * Builds the user content.
	 *
	 * @param maskedQuestion the question, already through {@link TypedTextMasker}
	 * @param history the recent turns, oldest first, already masked
	 */
	public String build(ChatReport report, List<PageChunk> chunks, List<ChatTurn> history,
			String maskedQuestion) {

		StringBuilder prompt = new StringBuilder();

		block(prompt, "report", report.maskedText());
		block(prompt, "results", resultsOf(report));
		block(prompt, "reference", referenceOf(chunks));
		block(prompt, "history", historyOf(history));
		block(prompt, "question", maskedQuestion);

		return prompt.toString().strip();
	}

	/**
	 * The validated results, one per line.
	 *
	 * <p>Lines rather than JSON. The model is being asked to read them, not parse them, and
	 * JSON spends tokens on punctuation - which on a free tier is the budget. The flag is
	 * spelled out because the prompt tells the model to trust it over its own reading.
	 */
	private static String resultsOf(ChatReport report) {
		StringBuilder results = new StringBuilder();

		if (report.biomarkers().isEmpty()) {
			results.append("No results were found on this report.\n");
		}
		for (ChatReport.ChatBiomarker biomarker : report.biomarkers()) {
			results.append(biomarker.testName())
					.append(": ").append(biomarker.rawValue());
			if (biomarker.unit() != null && !biomarker.unit().isBlank()) {
				results.append(' ').append(biomarker.unit());
			}
			if (biomarker.referenceRangeText() != null && !biomarker.referenceRangeText().isBlank()) {
				results.append(" (reference range as printed: ")
						.append(biomarker.referenceRangeText()).append(')');
			}
			results.append(" - flag: ").append(biomarker.flag()).append('\n');
		}
		if (report.collectedOn() != null) {
			results.append("Collected on: ").append(report.collectedOn()).append('\n');
		}
		return results.toString().strip();
	}

	/**
	 * The retrieved chunks, each labelled with where it came from.
	 *
	 * <p>The label is what lets an answer say "our HDL page explains..." without the model
	 * inventing a source, and it matches what the {@code sources} event sends the client.
	 */
	private static String referenceOf(List<PageChunk> chunks) {
		if (chunks.isEmpty()) {
			// Said explicitly rather than left blank. An empty block invites the model to fill
			// the gap from memory, which is the one thing it must not do.
			return "No reference pages were found for this question. "
					+ "Answer from the report alone, or say it is not covered.";
		}
		StringBuilder reference = new StringBuilder();
		for (PageChunk chunk : chunks) {
			reference.append('[').append(chunk.slug()).append(" · ").append(chunk.heading())
					.append("]\n").append(chunk.text()).append("\n\n");
		}
		return reference.toString().strip();
	}

	/**
	 * The recent turns, oldest dropped first to fit the budget.
	 *
	 * <p>Oldest first because the most recent turn is what a follow-up like "and the other
	 * one?" refers to. Dropping from the wrong end would keep the context that no longer
	 * matters and lose the one the question depends on.
	 */
	private String historyOf(List<ChatTurn> history) {
		if (history.isEmpty()) {
			return "This is the first question in this conversation.";
		}

		int budget = historyTokenBudget * CHARS_PER_TOKEN;
		List<String> lines = new java.util.ArrayList<>();
		int used = 0;

		for (int i = history.size() - 1; i >= 0; i--) {
			ChatTurn turn = history.get(i);
			String line = turn.role() + ": " + turn.content();
			if (used + line.length() > budget && !lines.isEmpty()) {
				break;
			}
			lines.addFirst(line);
			used += line.length();
		}
		return String.join("\n", lines);
	}

	/** One delimited block, with its content escaped so it cannot close the tag. */
	private static void block(StringBuilder prompt, String tag, String content) {
		prompt.append('<').append(tag).append(">\n")
				.append(escape(content))
				.append("\n</").append(tag).append(">\n\n");
	}

	/**
	 * Replaces angle brackets with look-alikes.
	 *
	 * <p>Reversible by eye and cheap: the model is told what the substitution is, so a printed
	 * reference range of "&lt;100" still reads as "less than 100" rather than being mangled or
	 * dropped. Deleting the brackets instead would silently change what a range means, which on
	 * a lab report is the difference between "under 100" and "100".
	 */
	static String escape(String content) {
		return content.replace('<', '‹').replace('>', '›');
	}
}
