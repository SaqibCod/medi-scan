package dev.saq.mediscan.chat;

/**
 * One retrievable piece of a biomarker page.
 *
 * <p>A row of {@code vector_store}, before or after its embedding exists. The fields are real
 * columns rather than a metadata blob because {@link #slug()} is what re-indexing deletes by
 * and what the report boost looks up, and because {@link #title()} and {@link #heading()} are
 * sent to the client in the {@code sources} SSE event (contract 4.4) - so the retriever must
 * not have to re-read the markdown to answer.
 *
 * @param slug the page this came from
 * @param title the page's display name
 * @param heading the H2 section this piece belongs to. Several pieces of one long section all
 *     report the same heading: it names the slot, not the piece
 * @param chunkIndex position within the page, so the pieces of a split section stay ordered
 * @param text the chunk, opening with "{title} - {heading}" so it still makes sense when it is
 *     retrieved on its own and placed beside chunks from other pages
 */
public record PageChunk(
		String slug,
		String title,
		String heading,
		int chunkIndex,
		String text) {

	/**
	 * Hides the text.
	 *
	 * <p>{@code CLAUDE.md} rule 19: a retrieved page's slug is reference content and may be
	 * logged, its text may not. These travel through retrieval, the prompt builder and the
	 * indexer, so the one record most likely to end up in a log line is this one.
	 */
	@Override
	public String toString() {
		return "PageChunk[slug=" + slug + ", heading=" + heading + ", chunkIndex=" + chunkIndex
				+ ", chars=" + text.length() + "]";
	}
}
