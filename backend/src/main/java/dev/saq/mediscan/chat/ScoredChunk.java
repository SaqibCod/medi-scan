package dev.saq.mediscan.chat;

/**
 * A retrieved chunk and how close it was to the question.
 *
 * @param chunk the page content
 * @param similarity cosine similarity, 1 for identical and 0 for unrelated. Computed as
 *     {@code 1 - (embedding <=> query)}, so it reads the way a reader expects rather than as a
 *     distance where smaller is better
 */
public record ScoredChunk(PageChunk chunk, double similarity) {

	/** The similarity rounded to 2 decimals, which is the only part of this that is logged. */
	public double roundedSimilarity() {
		return Math.round(similarity * 100.0) / 100.0;
	}

	/** Delegates to {@link PageChunk#toString()}, which already hides the text. */
	@Override
	public String toString() {
		return "ScoredChunk[" + chunk + ", similarity=" + roundedSimilarity() + "]";
	}
}
