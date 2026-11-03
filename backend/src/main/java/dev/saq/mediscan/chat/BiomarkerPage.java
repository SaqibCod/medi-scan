package dev.saq.mediscan.chat;

import java.time.LocalDate;
import java.util.List;

/**
 * One curated biomarker page, as the indexer sees it.
 *
 * <p>The backend's copy of {@code client/content/biomarkers/<slug>.md}, synced by
 * {@code scripts/sync-biomarker-content.sh} and guarded by {@code BiomarkerContentSyncTest}
 * ({@code CLAUDE.md} rule 20). Only the fields indexing and retrieval actually need are parsed:
 * the client renders the rest, and a field this class does not read is a field that cannot
 * drift.
 *
 * @param slug the page id, equal to the filename and to an entry in {@code index.json}
 * @param title the display name, which every chunk carries into the prompt
 * @param panel the panel the test usually belongs to
 * @param aliases other names for the test, used by the report boost to notice that a question
 *     is about a marker on this report
 * @param measures the one-line description from the frontmatter
 * @param reviewedOn the date a person reviewed this page, or null. Null means the page is not
 *     indexed at all when {@code rag.require-reviewed} is on (LLD C5)
 * @param body the markdown below the frontmatter
 * @param contentHash SHA-256 of the whole file, hex encoded. What {@code content_index} stores
 *     and compares, so an unchanged page costs no embedding quota on restart
 */
public record BiomarkerPage(
		String slug,
		String title,
		String panel,
		List<String> aliases,
		String measures,
		LocalDate reviewedOn,
		String body,
		String contentHash) {

	public BiomarkerPage {
		aliases = List.copyOf(aliases);
	}

	/** Whether a person has reviewed this page. */
	public boolean isReviewed() {
		return reviewedOn != null;
	}

	/**
	 * Hides the body.
	 *
	 * <p>Reference content rather than report data, but {@code CLAUDE.md} rule 19 draws the
	 * line at the text of a retrieved page: a slug may be logged, its prose may not. A record
	 * prints every field by default, and this one travels through the indexer, so one
	 * {@code log.debug(page)} would put a whole page in the log.
	 */
	@Override
	public String toString() {
		return "BiomarkerPage[slug=" + slug + ", reviewedOn=" + reviewedOn
				+ ", bodyChars=" + body.length() + "]";
	}
}
