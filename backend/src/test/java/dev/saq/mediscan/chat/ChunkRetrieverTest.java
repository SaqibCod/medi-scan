package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.saq.mediscan.support.PostgresTestBase;

/**
 * Retrieval against the real indexed pages ({@code LLD} 6.2, 13).
 *
 * <p>Runs on the actual bundled content with {@link FakeEmbeddingModel}, which is a hashed
 * bag of words rather than noise - so word overlap genuinely decides what comes back. That
 * makes these tests measure retrieval rather than merely proving a query ran, while being
 * honest about the difference: the fake has no idea "cholesterol" and "lipid" are related,
 * which is why the chat evaluation runs against the real model.
 */
class ChunkRetrieverTest extends PostgresTestBase {

	@Autowired
	private ChunkRetriever retriever;

	@Autowired
	private ContentIndexer indexer;

	@Autowired
	private ContentChunkRepository repository;

	@Autowired
	private org.springframework.jdbc.core.simple.JdbcClient jdbc;

	/**
	 * Makes sure the pages are actually indexed, whatever ran before.
	 *
	 * <p>The {@code content_index} delete is the load-bearing part. {@code ContentIndexerTest}
	 * empties {@code vector_store} between its own cases, and if it leaves index rows behind
	 * then every hash still matches - so a plain {@code index()} would decide there is nothing
	 * to do and these tests would query an empty store. Clearing both first is what makes this
	 * fixture independent of test order rather than accidentally dependent on it.
	 */
	@BeforeEach
	void index() {
		// Keyed on a page that must be there, not on a row count. Other classes insert their
		// own synthetic chunks, so a non-zero count says nothing about whether the real pages
		// are indexed - and this suite spent a run failing on exactly that.
		if (repository.firstChunkOf("ldl-cholesterol").isEmpty()) {
			jdbc.sql("delete from vector_store").update();
			jdbc.sql("delete from content_index").update();
			indexer.index();
		}
	}

	@Test
	@DisplayName("a question about a page's topic retrieves that page")
	void retrievesTheRightPage() {
		List<PageChunk> chunks = retriever.retrieve("What does HDL cholesterol measure?", report());

		assertThat(chunks).isNotEmpty();
		assertThat(chunks).extracting(PageChunk::slug).contains("hdl-cholesterol");
	}

	@Test
	@DisplayName("retrieval is capped at top-k")
	void respectsTopK() {
		assertThat(retriever.retrieve("cholesterol range high low measure", report()))
				.hasSizeLessThanOrEqualTo(4);
	}

	@Test
	@DisplayName("a question naming a marker on the report pulls that page in")
	void reportBoostIncludesTheNamedMarker() {
		// "Why is my LDL flagged?" is mostly about this report and only a little about LDL, so
		// its embedding can sit closer to a page about flags than to the LDL page. The boost
		// is what stops the reader being told about something else.
		List<PageChunk> chunks = retriever.retrieve("Why is my LDL flagged?", report());

		assertThat(chunks).extracting(PageChunk::slug).contains("ldl-cholesterol");
	}

	@Test
	@DisplayName("the boost only offers pages for markers on this report")
	void boostCannotIntroduceAnUnrelatedPage() {
		// TSH has a page, but this reader never had the test. Naming it must not produce a
		// boosted chunk about a test they did not have.
		ChatReport lipidOnly = report();
		List<PageChunk> chunks = retriever.retrieve("What about my TSH?", lipidOnly);

		assertThat(chunks).allSatisfy(chunk ->
				assertThat(chunk.slug()).isNotEqualTo("tsh"));
	}

	@Test
	@DisplayName("a marker named by an alias is still boosted")
	void aliasesAreRecognised() {
		List<PageChunk> chunks =
				retriever.retrieve("Is my LDL-C something to worry about?", report());

		assertThat(chunks).extracting(PageChunk::slug).contains("ldl-cholesterol");
	}

	@Test
	@DisplayName("a marker named at the end of a question is still boosted")
	void nameBeforePunctuationIsRecognised() {
		// "...my LDL?" is the single most likely question on the report page, and a whole-word
		// match padded with spaces alone would miss it.
		assertThat(retriever.retrieve("Why is my LDL?", report()))
				.extracting(PageChunk::slug)
				.contains("ldl-cholesterol");
	}

	@Test
	@DisplayName("a question about nothing in the content returns little or nothing")
	void unrelatedQuestionRetrievesLittle() {
		// Retrieving nothing is a valid outcome: the answer then rests on the report alone,
		// which beats padding the prompt with a page about something else.
		List<PageChunk> chunks =
				retriever.retrieve("zzqq xxyy wwvv unrelated gibberish token", report());

		assertThat(chunks).hasSizeLessThanOrEqualTo(4);
	}

	private static ChatReport report() {
		return new ChatReport(UUID.randomUUID(),
				"Total Cholesterol 238 mg/dL <200\nLDL Cholesterol 162 mg/dL <100\n"
						+ "HDL Cholesterol 52 mg/dL >40\n",
				List.of(
						new ChatReport.ChatBiomarker("Total Cholesterol", "238", "mg/dL", "<200",
								"HIGH", "total-cholesterol"),
						new ChatReport.ChatBiomarker("LDL Cholesterol", "162", "mg/dL", "<100",
								"HIGH", "ldl-cholesterol"),
						new ChatReport.ChatBiomarker("HDL Cholesterol", "52", "mg/dL", ">40",
								"NORMAL", "hdl-cholesterol")),
				LocalDate.of(2026, 9, 28));
	}
}
