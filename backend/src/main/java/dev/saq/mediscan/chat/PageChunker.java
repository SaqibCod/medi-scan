package dev.saq.mediscan.chat;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Splits a biomarker page into the pieces that get embedded and retrieved ({@code LLD} 5).
 *
 * <p><strong>H2 headings are the split points.</strong> 7b's content rules fix those headings
 * for exactly this reason: "What it measures", "What high X means", "What low X means", "What
 * can affect the result" and "Questions to ask your doctor" are five slots with five jobs, and
 * a reader's question is nearly always about one of them. Splitting on a character count
 * instead would cut an explanation in half and let retrieval return the half that answers
 * nothing.
 *
 * <p><strong>Every chunk restates where it came from.</strong> A chunk is embedded alone,
 * retrieved alone, and lands in the prompt beside chunks from other pages. "A value above the
 * range printed on your report means..." is useless without knowing which test it is about, so
 * each chunk's text opens with "{title} - {heading}". That also gives the embedding the page's
 * name, which is most of what makes a question like "what does HDL measure" match.
 *
 * <p>A plain class with no dependencies, so the splitting rules can be table-tested without a
 * context.
 */
@Component
public class PageChunker {

	/**
	 * Roughly 500 tokens, which {@code LLD} 5 gives as the ceiling for one chunk.
	 *
	 * <p>Characters rather than tokens because counting tokens needs the provider's tokeniser,
	 * and this does not need to be exact - it decides whether to split a section, not whether a
	 * request fits. Four characters per token is the usual English approximation.
	 */
	private static final int MAX_CHUNK_CHARS = 2000;

	private static final String HEADING_PREFIX = "## ";

	/** Separates the "{title} - {heading}" opener from the section body. */
	private static final String CONTEXT_SEPARATOR = "\n\n";

	/**
	 * Chunks one page.
	 *
	 * @return the page's chunks in reading order, with {@code chunkIndex} running from 0. Empty
	 *     when the page has no H2 section with a body, which is a malformed page rather than an
	 *     error here - {@code ContentIndexer} logs the count and moves on
	 */
	public List<PageChunk> chunk(BiomarkerPage page) {
		List<PageChunk> chunks = new ArrayList<>();
		int index = 0;

		for (Section section : sectionsOf(page.body())) {
			for (String piece : split(section.body())) {
				chunks.add(new PageChunk(page.slug(), page.title(), section.heading(), index++,
						opener(page.title(), section.heading()) + CONTEXT_SEPARATOR + piece));
			}
		}
		return List.copyOf(chunks);
	}

	private static String opener(String title, String heading) {
		return title + " - " + heading;
	}

	/**
	 * The page's H2 sections.
	 *
	 * <p>Only {@code ## } starts a new one. An H3 inside a section is part of the explanation
	 * around it, and anything above the first H2 belongs to no slot - the frontmatter is
	 * already gone by this point, so that can only be stray prose.
	 */
	private static List<Section> sectionsOf(String body) {
		List<Section> sections = new ArrayList<>();
		String heading = null;
		StringBuilder current = new StringBuilder();

		for (String line : body.split("\n", -1)) {
			if (line.startsWith(HEADING_PREFIX)) {
				addIfComplete(sections, heading, current);
				heading = line.substring(HEADING_PREFIX.length()).strip();
				current = new StringBuilder();
			}
			else if (heading != null) {
				current.append(line).append('\n');
			}
		}
		addIfComplete(sections, heading, current);
		return sections;
	}

	/**
	 * Keeps a section only when it has a body.
	 *
	 * <p>An empty section would embed to a vector of its heading alone, which can out-rank a
	 * real chunk on any question that happens to echo the heading - and then contribute nothing
	 * to the answer.
	 */
	private static void addIfComplete(List<Section> sections, String heading,
			StringBuilder body) {

		if (heading == null) {
			return;
		}
		String text = body.toString().strip();
		if (!text.isEmpty()) {
			sections.add(new Section(heading, text));
		}
	}

	/**
	 * Splits an over-long section on paragraph boundaries.
	 *
	 * <p>Paragraphs, never mid-sentence. A chunk cut in the middle of a sentence embeds badly
	 * and reads worse if it reaches the prompt. A single paragraph that is itself over the
	 * limit is therefore kept whole: there is nowhere safe to cut it, and one oversized chunk
	 * costs a slightly blurrier embedding where a torn sentence costs meaning.
	 */
	private static List<String> split(String section) {
		if (section.length() <= MAX_CHUNK_CHARS) {
			return List.of(section);
		}

		List<String> pieces = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (String paragraph : section.split("\n\\s*\n")) {
			String trimmed = paragraph.strip();
			if (trimmed.isEmpty()) {
				continue;
			}
			boolean wouldOverflow = !current.isEmpty()
					&& current.length() + trimmed.length() > MAX_CHUNK_CHARS;
			if (wouldOverflow) {
				pieces.add(current.toString().strip());
				current = new StringBuilder();
			}
			current.append(trimmed).append("\n\n");
		}

		if (!current.isEmpty()) {
			pieces.add(current.toString().strip());
		}
		return pieces.isEmpty() ? List.of(section) : List.copyOf(pieces);
	}

	private record Section(String heading, String body) {
	}
}
