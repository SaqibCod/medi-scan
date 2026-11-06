package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

/**
 * Splitting a biomarker page into retrievable chunks ({@code LLD} 5).
 *
 * <p>The H2 headings are the split points, which is why 7b's content rules call them fixed:
 * "What it measures", "What high X means", and so on are five slots with five jobs, and a
 * question is nearly always about one of them. Splitting anywhere else - every N characters,
 * say - would cut an explanation in half and retrieve the half that does not answer anything.
 *
 * <p>Every chunk carries its page title and heading in its own text. A chunk is retrieved
 * alone and lands in the prompt beside chunks from other pages, so "A value above the range
 * printed on your report means..." has to say what it is a value *of*.
 */
class PageChunkerTest {

	private final PageChunker chunker = new PageChunker();

	@Nested
	@DisplayName("splitting on headings")
	class Headings {

		@Test
		@DisplayName("one chunk per H2 section, in page order")
		void oneChunkPerSection() {
			List<PageChunk> chunks = chunker.chunk(page("""
					## What it measures

					The first thing.

					## What high LDL means

					The second thing.

					## What can affect the result

					The third thing.
					"""));

			assertThat(chunks).extracting(PageChunk::heading).containsExactly(
					"What it measures", "What high LDL means", "What can affect the result");
			assertThat(chunks).extracting(PageChunk::chunkIndex).containsExactly(0, 1, 2);
			assertThat(chunks).allSatisfy(chunk ->
					assertThat(chunk.slug()).isEqualTo("ldl-cholesterol"));
		}

		@Test
		@DisplayName("each chunk's text opens with the page title and its heading")
		void textCarriesItsOwnContext() {
			PageChunk chunk = chunker.chunk(page("""
					## What it measures

					Cholesterol carried by LDL particles.
					""")).getFirst();

			// Retrieved on its own, next to chunks from other pages. Without this the model
			// cannot tell which test the sentence is about.
			assertThat(chunk.text()).startsWith("LDL Cholesterol - What it measures");
			assertThat(chunk.text()).contains("Cholesterol carried by LDL particles.");
		}

		@Test
		@DisplayName("H3 headings stay inside their section")
		void deeperHeadingsDoNotSplit() {
			// Only H2 is a slot boundary. An H3 is part of the explanation around it.
			List<PageChunk> chunks = chunker.chunk(page("""
					## What it measures

					Intro.

					### A detail

					More about the same thing.
					"""));

			assertThat(chunks).hasSize(1);
			assertThat(chunks.getFirst().text()).contains("### A detail");
		}

		@Test
		@DisplayName("text before the first heading is ignored")
		void preambleIsDropped() {
			// Frontmatter is already stripped by the loader; anything left above the first H2
			// is stray prose with no slot, so it belongs to no chunk.
			List<PageChunk> chunks = chunker.chunk(page("""
					Some stray line.

					## What it measures

					The real content.
					"""));

			assertThat(chunks).hasSize(1);
			assertThat(chunks.getFirst().text()).doesNotContain("Some stray line");
		}

		@Test
		@DisplayName("a section with no body is dropped")
		void emptySectionsAreDropped() {
			List<PageChunk> chunks = chunker.chunk(page("""
					## What it measures

					## What high LDL means

					Real content.
					"""));

			// An empty chunk embeds to a vector of its heading alone and can out-rank a real
			// one on a question that happens to echo the heading.
			assertThat(chunks).hasSize(1);
			assertThat(chunks.getFirst().heading()).isEqualTo("What high LDL means");
		}

		@Test
		@DisplayName("a page with no headings produces no chunks")
		void noHeadingsNoChunks() {
			assertThat(chunker.chunk(page("Just prose, no structure."))).isEmpty();
		}
	}

	@Nested
	@DisplayName("splitting a long section")
	class LongSections {

		@Test
		@DisplayName("a section over the limit splits on a paragraph boundary")
		void splitsOnParagraphs() {
			String paragraph = "word ".repeat(200).strip();
			List<PageChunk> chunks = chunker.chunk(page("""
					## What it measures

					%s

					%s

					%s
					""".formatted(paragraph, paragraph, paragraph)));

			assertThat(chunks).hasSizeGreaterThan(1);
			// Mid-sentence cuts are what this exists to avoid: every piece ends where a
			// paragraph ended.
			assertThat(chunks).allSatisfy(chunk ->
					assertThat(chunk.text().strip()).endsWith("word"));
		}

		@Test
		@DisplayName("every piece of a split section keeps the same heading and rising indexes")
		void splitPiecesStayAddressable() {
			String paragraph = "word ".repeat(200).strip();
			List<PageChunk> chunks = chunker.chunk(page("""
					## What it measures

					%s

					%s

					%s

					## What can affect the result

					Short.
					""".formatted(paragraph, paragraph, paragraph)));

			List<PageChunk> measures = chunks.stream()
					.filter(chunk -> chunk.heading().equals("What it measures"))
					.toList();

			assertThat(measures).hasSizeGreaterThan(1);
			// The sources line in the SSE event names a page and a heading, so both pieces have
			// to report the same heading rather than an invented "part 2".
			assertThat(measures).allSatisfy(chunk ->
					assertThat(chunk.text()).startsWith("LDL Cholesterol - What it measures"));

			assertThat(chunks).extracting(PageChunk::chunkIndex)
					.containsExactlyElementsOf(
							java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
		}

		@Test
		@DisplayName("a single paragraph longer than the limit is kept whole")
		void oversizedParagraphIsNotCutMidSentence() {
			// Nowhere safe to cut. Better one oversized chunk than a sentence torn in half:
			// the embedding is a little blurrier, the text still reads.
			String huge = "word ".repeat(900).strip();
			List<PageChunk> chunks = chunker.chunk(page("""
					## What it measures

					%s
					""".formatted(huge)));

			assertThat(chunks).hasSize(1);
			assertThat(chunks.getFirst().text()).contains(huge);
		}
	}

	@Test
	@DisplayName("a chunk never prints its text")
	void chunkHidesItsText() {
		PageChunk chunk = chunker.chunk(page("""
				## What it measures

				Distinctive sentence that must not be logged.
				""")).getFirst();

		// Reference content, not report data - but rule 19 draws the line at a chunk's text,
		// and a record prints its fields by default.
		assertThat(chunk.toString())
				.doesNotContain("Distinctive sentence")
				.contains("ldl-cholesterol")
				.contains("What it measures");
	}

	private static BiomarkerPage page(String body) {
		return new BiomarkerPage("ldl-cholesterol", "LDL Cholesterol", "Lipid panel",
				List.of("LDL", "LDL-C"), "Cholesterol carried by LDL particles", null, body,
				"hash");
	}
}
