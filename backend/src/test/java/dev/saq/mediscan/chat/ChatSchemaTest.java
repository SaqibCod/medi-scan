package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.support.PostgresTestBase;

/**
 * What {@code V5__chat_rag.sql} actually produced.
 *
 * <p>Three things are checked here that Java-side testing cannot establish.
 *
 * <p><strong>The privacy shape.</strong> Neither new table may have a column that could hold
 * report data or anything a user typed - no report id, no session id, no user id, no question,
 * no answer. A column list is the only way to assert the absence of a field, and it is the
 * assertion that keeps rule 9 ("chat messages are never stored") true by construction rather
 * than by review. It also explains why neither table is in the retention cascade: there is
 * nothing in them belonging to anyone.
 *
 * <p><strong>The vector width.</strong> 768 is fixed by the column, not by configuration, so a
 * vector of any other size has to be refused by the database. The test inserts one to prove it.
 *
 * <p><strong>Cosine distance works on the stored type.</strong> The retriever ranks with
 * {@code <=>}; if the extension or the operator class were missing, every search would fail at
 * runtime rather than here.
 */
class ChatSchemaTest extends PostgresTestBase {

	@Autowired
	private JdbcClient jdbc;

	/**
	 * Removes the synthetic chunks this class inserts.
	 *
	 * <p>Not tidiness. {@code vector_store} is shared across the whole suite, and a fixture
	 * elsewhere that asks "is anything indexed?" would see these three rows and conclude the
	 * real pages were loaded - which is exactly how {@code ChunkRetrieverTest} once failed
	 * only when run with the rest of the suite.
	 */
	@org.junit.jupiter.api.AfterEach
	void removeSyntheticChunks() {
		jdbc.sql("delete from vector_store where slug in ('too-narrow', 'right-width', 'dupe', 'near')")
				.update();
	}

	@Test
	@DisplayName("vector_store holds reference content only - no owner, no report")
	void vectorStoreHoldsNoReportData() {
		List<String> columns = columnsOf("vector_store");

		assertThat(columns).containsExactlyInAnyOrder(
				"id", "slug", "title", "heading", "chunk_index", "content", "embedding");

		// The absence is the point. A column here that belonged to someone would put this
		// table in the retention cascade, and it is deliberately outside it.
		assertThat(columns)
				.as("vector_store must never hold report or caller data")
				.doesNotContain("report_id", "session_id", "user_id", "owner_id",
						"question", "answer", "expires_at");
	}

	@Test
	@DisplayName("content_index records the model and size beside the hash")
	void contentIndexRecordsModelAndSize() {
		// All three are needed to decide whether a page must be re-embedded: a hash match means
		// nothing if the vectors came from a different model or were cut to a different width.
		assertThat(columnsOf("content_index")).containsExactlyInAnyOrder(
				"slug", "content_hash", "embedding_model", "dimensions", "chunk_count",
				"indexed_at");
	}

	@Test
	@DisplayName("the embedding column refuses a vector that is not 768 wide")
	void embeddingWidthIsFixedBySchema() {
		assertThatThrownBy(() -> insertChunk("too-narrow", vectorLiteral(512)))
				.as("a 512-wide vector must be refused by the column, not accepted and ranked")
				.isInstanceOf(Exception.class);

		// The configured width, accepted.
		insertChunk("right-width", vectorLiteral(768));
		assertThat(countBySlug("right-width")).isOne();
	}

	@Test
	@DisplayName("one page cannot have two chunks at the same position")
	void chunkPositionIsUniquePerPage() {
		insertChunk("dupe", vectorLiteral(768), 0);

		assertThatThrownBy(() -> insertChunk("dupe", vectorLiteral(768), 0))
				.as("(slug, chunk_index) is unique so re-indexing can upsert safely")
				.isInstanceOf(Exception.class);
	}

	@Test
	@DisplayName("cosine distance ranks the stored vectors")
	void cosineDistanceWorks() {
		insertChunk("near", vectorLiteral(768), 0);

		// 1 - (a <=> b) is the similarity ChunkRetriever reports. A vector compared with itself
		// is similarity 1, which is the only value that needs no tolerance argument.
		Double similarity = jdbc.sql("""
				select 1 - (embedding <=> embedding) from vector_store where slug = 'near'
				""")
				.query(Double.class)
				.single();

		assertThat(similarity).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("daily_stats gained the five chat counters, all defaulting to zero")
	void dailyStatsGainedChatCounters() {
		assertThat(columnsOf("daily_stats")).contains(
				"chat_answers", "chat_cache_hits", "chat_declined", "chat_not_in_context",
				"chat_number_stops");

		// DEFAULT 0 matters: StatsRecorder's upsert names only the column it is incrementing,
		// so a NULL default would make the first increment of the day a no-op.
		jdbc.sql("insert into daily_stats (day) values (date '2099-01-01')").update();
		assertThat(jdbc.sql("""
				select chat_answers + chat_cache_hits + chat_declined
				     + chat_not_in_context + chat_number_stops
				  from daily_stats where day = date '2099-01-01'
				""").query(Integer.class).single()).isZero();

		jdbc.sql("delete from daily_stats where day = date '2099-01-01'").update();
	}

	// --- helpers ------------------------------------------------------------

	private void insertChunk(String slug, String embedding) {
		insertChunk(slug, embedding, 0);
	}

	private void insertChunk(String slug, String embedding, int chunkIndex) {
		jdbc.sql("""
				insert into vector_store (id, slug, title, heading, chunk_index, content, embedding)
				values (:id, :slug, 'Title', 'What it measures', :idx, 'text', cast(:embedding as vector))
				""")
				.param("id", UUID.randomUUID())
				.param("slug", slug)
				.param("idx", chunkIndex)
				.param("embedding", embedding)
				.update();
	}

	private int countBySlug(String slug) {
		return jdbc.sql("select count(*) from vector_store where slug = :slug")
				.param("slug", slug)
				.query(Integer.class)
				.single();
	}

	/** {@code [0.1,0.1,...]} with {@code size} components, which is pgvector's text form. */
	private static String vectorLiteral(int size) {
		StringBuilder literal = new StringBuilder(size * 4 + 2).append('[');
		for (int i = 0; i < size; i++) {
			if (i > 0) {
				literal.append(',');
			}
			literal.append("0.1");
		}
		return literal.append(']').toString();
	}

	private List<String> columnsOf(String table) {
		return jdbc.sql("""
				select column_name from information_schema.columns
				 where table_schema = 'public' and table_name = :table
				""")
				.param("table", table)
				.query(String.class)
				.list();
	}
}
