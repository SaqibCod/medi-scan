package dev.saq.mediscan.llm;

/**
 * Which kind of work a model call belongs to.
 *
 * <p>An enum rather than a free string because it is a dimension of the usage log and the
 * token stats, and because it is the only thing about a call that is safe to log. It also
 * documents the call budget: a report costs exactly two calls, and the second never sees the
 * report text ({@code CLAUDE.md} rule 5); an uncached chat answer costs two more, one to embed
 * the question and one to write the answer ({@code docs/dataflow.md} 9.3).
 *
 * <p>Not every purpose has a prompt. {@link #EMBED} sends text to be turned into a vector and
 * has no instructions at all, which is why {@link PromptTemplates} asks each purpose whether it
 * carries one instead of assuming.
 */
public enum LlmPurpose {

	/** Step 1: read the printed values out of the masked report text. */
	EXTRACT,

	/** Step 2: write the patient summary from the validated values only. */
	SUMMARY,

	/** Phase 5: answer one question about a report, streamed. */
	CHAT,

	/**
	 * Phase 5: turn text into a vector - a biomarker page chunk while indexing, or a masked
	 * question at retrieval time.
	 *
	 * <p>Counted against the same daily cap as the three above, because it is the same budget
	 * and the same provider. It is the one purpose with no prompt.
	 */
	EMBED;

	/**
	 * Whether this purpose sends a system prompt.
	 *
	 * <p>Only {@link #EMBED} does not. Without this, {@link PromptTemplates} would demand a
	 * prompt file for every enum value and fail startup the moment a non-prompt purpose was
	 * added.
	 */
	public boolean hasPrompt() {
		return this != EMBED;
	}

	/** Lowercase, for log lines and the {@code purpose} column. */
	public String label() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
