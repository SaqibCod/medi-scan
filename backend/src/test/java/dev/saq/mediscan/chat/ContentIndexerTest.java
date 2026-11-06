package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.support.PostgresTestBase;

/**
 * Indexing the bundled pages ({@code LLD} 13, backend integration case 1).
 *
 * <p>The claim being tested is a cost one: <strong>a restart must spend nothing</strong>.
 * Embedding comes out of the same daily budget as answering, so a deploy that re-embedded
 * twenty unchanged pages would burn the demo's quota doing work with no result. The counter on
 * {@link FakeEmbeddingModel} is requests, not texts, because a request is what the cap counts.
 *
 * <p>The other half is correctness under change: an edited page re-embeds alone, and a
 * different model or vector width re-embeds everything, because vectors from two models rank
 * against each other as confident nonsense.
 */
class ContentIndexerTest extends PostgresTestBase {

	@Autowired
	private ContentIndexer indexer;

	@Autowired
	private ContentChunkRepository repository;

	@Autowired
	private FakeEmbeddingModel embeddingModel;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void clearIndex() {
		jdbc.sql("delete from vector_store").update();
		jdbc.sql("delete from content_index").update();
		embeddingModel.reset();
	}

	@Test
	@DisplayName("the first pass embeds every page and the second embeds none")
	void secondPassCostsNothing() {
		ContentIndexer.Result first = indexer.index();

		assertThat(first.embedded()).isPositive();
		assertThat(first.unchanged()).isZero();
		assertThat(first.chunksWritten()).isPositive();
		assertThat(repository.chunkCount()).isEqualTo(first.chunksWritten());

		int callsAfterFirst = embeddingModel.callCount();
		assertThat(callsAfterFirst).isPositive();

		ContentIndexer.Result second = indexer.index();

		assertThat(second.embedded()).isZero();
		assertThat(second.unchanged()).isEqualTo(first.embedded());
		// The number that matters: not one further request against the daily cap.
		assertThat(embeddingModel.callCount())
				.as("a restart must not spend embedding quota")
				.isEqualTo(callsAfterFirst);
		assertThat(repository.chunkCount()).isEqualTo(first.chunksWritten());
	}

	@Test
	@DisplayName("a changed page re-embeds only itself")
	void changedPageReEmbedsAlone() {
		indexer.index();
		int callsAfterFirst = embeddingModel.callCount();

		// Rewrite one page's stored hash to something that cannot match, which is exactly what
		// an edit to the markdown would produce.
		jdbc.sql("update content_index set content_hash = 'stale' where slug = 'tsh'").update();

		ContentIndexer.Result result = indexer.index();

		assertThat(result.embedded()).isOne();
		assertThat(result.unchanged()).isEqualTo(result.scanned() - 1);
		assertThat(embeddingModel.callCount()).isGreaterThan(callsAfterFirst);
	}

	@Test
	@DisplayName("a different embedding model re-embeds everything")
	void modelChangeReEmbedsEverything() {
		ContentIndexer.Result first = indexer.index();

		// Vectors from two models are not comparable, so a store holding both would rank
		// nonsense fluently - four chunks come back and the answer still reads fine.
		jdbc.sql("update content_index set embedding_model = 'some-other-model'").update();

		assertThat(indexer.index().embedded()).isEqualTo(first.embedded());
	}

	@Test
	@DisplayName("a different vector width re-embeds everything")
	void widthChangeReEmbedsEverything() {
		ContentIndexer.Result first = indexer.index();

		jdbc.sql("update content_index set dimensions = 1536").update();

		assertThat(indexer.index().embedded()).isEqualTo(first.embedded());
	}

	@Test
	@DisplayName("a page that is no longer bundled is dropped from the index")
	void vanishedPageIsForgotten() {
		indexer.index();

		// A slug nothing on the classpath produces. A leftover row stays retrievable, so chat
		// would cite a page the reader cannot open.
		jdbc.sql("""
				insert into vector_store (id, slug, title, heading, chunk_index, content, embedding)
				values (gen_random_uuid(), 'ghost-marker', 'Ghost', 'What it measures', 0, 'text',
				        cast(:embedding as vector))
				""")
				.param("embedding", ContentChunkRepository.toVectorLiteral(
						FakeEmbeddingModel.embedText("ghost")))
				.update();
		jdbc.sql("""
				insert into content_index
				    (slug, content_hash, embedding_model, dimensions, chunk_count, indexed_at)
				values ('ghost-marker', 'h', 'fake-embedding', 768, 1, now())
				""").update();

		assertThat(indexer.index().removed()).isOne();
		assertThat(repository.indexedSlugs()).doesNotContain("ghost-marker");
	}

	@Test
	@DisplayName("require-reviewed leaves every unreviewed page out of the index")
	void requireReviewedSkipsEverythingUnreviewed() {
		// No page has been reviewed yet, so with the gate on the index must stay empty.
		// Unreviewed medical text reaching a live answer is the failure this prevents.
		ContentIndexer strict = new ContentIndexer(new BiomarkerPageLoader(), new PageChunker(),
				repository, gatewayStub(), reviewedOnlyProperties());

		ContentIndexer.Result result = strict.index();

		assertThat(result.skippedUnreviewed()).isEqualTo(result.scanned());
		assertThat(result.embedded()).isZero();
		assertThat(repository.chunkCount()).isZero();
	}

	@Test
	@DisplayName("an embedding failure leaves the existing index alone and does not throw")
	void failureKeepsTheExistingIndex() {
		int chunksBefore = indexer.index().chunksWritten();
		jdbc.sql("update content_index set content_hash = 'stale'").update();

		embeddingModel.failWith(new IllegalStateException("provider is down"));

		// indexQuietly is what the startup listener calls. It must not propagate: a failed
		// index is a worse chat, not a server that will not boot.
		indexer.indexQuietly();

		assertThat(repository.chunkCount())
				.as("the previous vectors must survive a failed pass")
				.isEqualTo(chunksBefore);
	}

	@Test
	@DisplayName("indexing batches its embedding requests")
	void requestsAreBatched() {
		ContentIndexer.Result result = indexer.index();

		// One request is one call against the cap however many texts it carries. Without
		// batching this would be one request per chunk.
		assertThat(embeddingModel.textCount()).isEqualTo(result.chunksWritten());
		assertThat(embeddingModel.callCount()).isLessThan(embeddingModel.textCount());
	}

	@Test
	@DisplayName("stored chunks carry their page's title and heading")
	void storedChunksAreAddressable() {
		indexer.index();

		List<String> slugs = repository.indexedSlugs();
		assertThat(slugs).contains("ldl-cholesterol");

		PageChunk chunk = repository.firstChunkOf("ldl-cholesterol").orElseThrow();
		assertThat(chunk.title()).isEqualTo("LDL Cholesterol");
		// The sources SSE event sends slug, title and heading, so all three have to survive
		// the round trip through the database.
		assertThat(chunk.heading()).isNotBlank();
		assertThat(chunk.text()).startsWith("LDL Cholesterol - ");
	}

	private EmbeddingGateway gatewayStub() {
		return new EmbeddingGateway(embeddingModel, capGuardThatAlwaysAllows(),
				new dev.saq.mediscan.llm.LlmUsageLogger(),
				dev.saq.mediscan.support.TestProperties.defaults());
	}

	private dev.saq.mediscan.llm.DailyCapGuard capGuardThatAlwaysAllows() {
		return new dev.saq.mediscan.llm.DailyCapGuard(jdbc, java.time.Clock.systemUTC(),
				dev.saq.mediscan.support.TestProperties.defaults());
	}

	private dev.saq.mediscan.config.MediScanProperties reviewedOnlyProperties() {
		return dev.saq.mediscan.support.TestProperties.withRag(4, 0.6, true);
	}
}
