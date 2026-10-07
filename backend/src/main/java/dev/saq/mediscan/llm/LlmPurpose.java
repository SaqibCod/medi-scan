package dev.saq.mediscan.llm;

/**
 * Which pipeline step a model call belongs to.
 *
 * <p>An enum rather than a free string because it is a dimension of the usage log and the
 * token stats, and because it is the only thing about a call that is safe to log. It also
 * documents the two-call design: there are exactly two model calls per report, and the second
 * never sees the report text ({@code CLAUDE.md} rule 5).
 */
public enum LlmPurpose {

	/** Step 1: read the printed values out of the masked report text. */
	EXTRACT,

	/** Step 2: write the patient summary from the validated values only. */
	SUMMARY;

	/** Lowercase, for log lines and the {@code purpose} column. */
	public String label() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
