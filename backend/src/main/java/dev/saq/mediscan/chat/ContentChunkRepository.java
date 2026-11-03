package dev.saq.mediscan.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.config.SqlTime;

/**
 * The two reference-content tables: {@code vector_store} and {@code content_index}.
 *
 * <p><strong>Why a hand-written repository and not Spring AI's {@code PgVectorStore}.</strong>
 * That store calls the embedding model itself, inside {@code add()} and
 * {@code similaritySearch()}. Doing so would route every embedding call around
 * {@link EmbeddingGateway} and therefore around the daily cap and the usage log, which
 * {@code CLAUDE.md} rule 8 does not allow. It also offers no way to ask for
 * {@code RETRIEVAL_DOCUMENT} when indexing and {@code RETRIEVAL_QUERY} when searching, a
 * distinction the embedding API supports and retrieval quality depends on. {@code LLD} C3
 * anticipates this and permits the fallback.
 *
 * <p>Neither table holds report data, so no method here takes an {@code OwnerRef} - the usual
 * rule for repositories in this codebase. That is a property of the content, not an exemption:
 * these rows are the same curated pages for every caller, which is also why they are outside
 * the retention cascade.
 *
 * <p>Vectors cross the boundary as pgvector's text form, {@code [0.1,0.2,...]}, cast in SQL.
 * The driver has no vector type, and the alternative - a {@code PGobject} per row - buys
 * nothing for a table written a few dozen rows at a time on startup.
 */
@Repository
public class ContentChunkRepository {

	private final JdbcClient jdbc;
	private final Clock clock;

	public ContentChunkRepository(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// --- indexing -----------------------------------------------------------

	/**
	 * Replaces one page's chunks and records what was indexed, atomically.
	 *
	 * <p>Delete-then-insert rather than a merge: a page that loses a section must lose those
	 * chunks, and matching old rows to new ones by position would keep a stale chunk whenever
	 * a section was removed from the middle. One transaction so a crash between the two leaves
	 * the page's old chunks in place rather than none at all - a stale answer beats no answer,
	 * and the next start re-indexes it anyway because the hash still will not match.
	 *
	 * @param vectors one per chunk, in the same order
	 */
	@Transactional
	public void replacePage(BiomarkerPage page, List<PageChunk> chunks, List<float[]> vectors,
			String embeddingModel, int dimensions) {

		if (chunks.size() != vectors.size()) {
			throw new IllegalArgumentException(
					"chunk and vector counts differ: " + chunks.size() + " vs " + vectors.size());
		}

		deleteBySlug(page.slug());

		for (int i = 0; i < chunks.size(); i++) {
			PageChunk chunk = chunks.get(i);
			jdbc.sql("""
					insert into vector_store
					    (id, slug, title, heading, chunk_index, content, embedding)
					values (:id, :slug, :title, :heading, :idx, :content, cast(:embedding as vector))
					""")
					.param("id", UUID.randomUUID())
					.param("slug", chunk.slug())
					.param("title", chunk.title())
					.param("heading", chunk.heading())
					.param("idx", chunk.chunkIndex())
					.param("content", chunk.text())
					.param("embedding", toVectorLiteral(vectors.get(i)))
					.update();
		}

		jdbc.sql("""
				insert into content_index
				    (slug, content_hash, embedding_model, dimensions, chunk_count, indexed_at)
				values (:slug, :hash, :model, :dimensions, :count, :at)
				on conflict (slug) do update set
				    content_hash = excluded.content_hash,
				    embedding_model = excluded.embedding_model,
				    dimensions = excluded.dimensions,
				    chunk_count = excluded.chunk_count,
				    indexed_at = excluded.indexed_at
				""")
				.param("slug", page.slug())
				.param("hash", page.contentHash())
				.param("model", embeddingModel)
				.param("dimensions", dimensions)
				.param("count", chunks.size())
				// Through SqlTime: the driver cannot infer a SQL type for Instant, which JPA
				// hides and hand-written JdbcClient statements do not.
				.param("at", SqlTime.utc(Instant.now(clock)))
				.update();
	}

	/** Removes a page's chunks and its index row. For a page that no longer exists. */
	@Transactional
	public void forget(String slug) {
		deleteBySlug(slug);
		jdbc.sql("delete from content_index where slug = :slug").param("slug", slug).update();
	}

	private void deleteBySlug(String slug) {
		jdbc.sql("delete from vector_store where slug = :slug").param("slug", slug).update();
	}

	/**
	 * What has already been indexed, by slug.
	 *
	 * <p>Read in one query rather than one per page: the comparison that decides whether to
	 * spend embedding quota should not itself cost twenty round trips on every start.
	 */
	public Map<String, IndexedPage> indexed() {
		Map<String, IndexedPage> bySlug = new LinkedHashMap<>();
		jdbc.sql("select slug, content_hash, embedding_model, dimensions from content_index")
				.query((rs, rowNum) -> new IndexedPage(
						rs.getString("slug"),
						rs.getString("content_hash"),
						rs.getString("embedding_model"),
						rs.getInt("dimensions")))
				.list()
				.forEach(page -> bySlug.put(page.slug(), page));
		return bySlug;
	}

	// --- retrieval ----------------------------------------------------------

	/**
	 * The closest chunks to {@code query}, best first.
	 *
	 * <p>Cosine, matching the HNSW index the migration created. It has to be cosine:
	 * gemini-embedding-001 normalises its output at the full 3072 dimensions but not when
	 * truncated to 768, so an inner-product ranking would be partly a ranking by vector length.
	 *
	 * <p>The threshold is applied in SQL rather than afterwards so the database can stop early,
	 * and so "nothing was close enough" costs one query rather than one query plus a filter
	 * over rows that were never going to be used.
	 */
	public List<ScoredChunk> search(float[] query, int topK, double minSimilarity) {
		return jdbc.sql("""
				select slug, title, heading, chunk_index, content,
				       1 - (embedding <=> cast(:query as vector)) as similarity
				  from vector_store
				 where 1 - (embedding <=> cast(:query as vector)) >= :minSimilarity
				 order by embedding <=> cast(:query as vector)
				 limit :topK
				""")
				.param("query", toVectorLiteral(query))
				.param("minSimilarity", minSimilarity)
				.param("topK", topK)
				.query((rs, rowNum) -> new ScoredChunk(
						new PageChunk(rs.getString("slug"), rs.getString("title"),
								rs.getString("heading"), rs.getInt("chunk_index"),
								rs.getString("content")),
						rs.getDouble("similarity")))
				.list();
	}

	/**
	 * A page's first chunk, which is its "What it measures" section.
	 *
	 * <p>For the report boost ({@code LLD} 6.2 step 3): when a question names a marker that is
	 * on this report and has a page, that page's opening section is worth including even if
	 * the similarity search did not reach it.
	 */
	public Optional<PageChunk> firstChunkOf(String slug) {
		return jdbc.sql("""
				select slug, title, heading, chunk_index, content
				  from vector_store
				 where slug = :slug
				 order by chunk_index
				 limit 1
				""")
				.param("slug", slug)
				.query((rs, rowNum) -> new PageChunk(rs.getString("slug"), rs.getString("title"),
						rs.getString("heading"), rs.getInt("chunk_index"), rs.getString("content")))
				.optional();
	}

	/** Chunks currently stored. Logged as a count on startup, and asserted by tests. */
	public int chunkCount() {
		return jdbc.sql("select count(*) from vector_store").query(Integer.class).single();
	}

	// --- helpers ------------------------------------------------------------

	/**
	 * pgvector's text form: {@code [0.1,0.2,0.3]}.
	 *
	 * <p>Built with a plain builder rather than string joining a stream, because this runs once
	 * per chunk over 768 floats and the allocation shows up on a 2 GB box during a full
	 * re-index.
	 */
	static String toVectorLiteral(float[] vector) {
		StringBuilder literal = new StringBuilder(vector.length * 12 + 2).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				literal.append(',');
			}
			literal.append(vector[i]);
		}
		return literal.append(']').toString();
	}

	/** Returns the distinct slugs currently in {@code vector_store}. */
	public List<String> indexedSlugs() {
		return new ArrayList<>(jdbc.sql("select distinct slug from vector_store order by slug")
				.query(String.class)
				.list());
	}

	/**
	 * One row of {@code content_index}.
	 *
	 * @param contentHash what the page hashed to when it was last embedded
	 * @param embeddingModel which model produced those vectors. A change re-embeds everything,
	 *     because vectors from two models are not comparable with each other
	 * @param dimensions the width those vectors were asked for, for the same reason
	 */
	public record IndexedPage(String slug, String contentHash, String embeddingModel,
			int dimensions) {

		/** Whether a page is already indexed in exactly this form. */
		public boolean matches(BiomarkerPage page, String model, int width) {
			return contentHash.equals(page.contentHash())
					&& embeddingModel.equals(model)
					&& dimensions == width;
		}
	}
}
