package dev.saq.mediscan.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Keeps {@code vector_store} in step with the bundled biomarker pages ({@code LLD} 5, C4).
 *
 * <p><strong>Restarts must be free.</strong> Embedding is a model call and comes out of the
 * same daily budget as answering, so re-embedding twenty unchanged pages on every deploy would
 * spend the demo's quota on work with no result. Each page's hash is stored beside its vectors
 * and compared first; a page that has not changed is skipped entirely.
 *
 * <p><strong>A model or width change re-embeds everything.</strong> Vectors from two different
 * models are not comparable with each other, so a store holding both would rank nonsense
 * convincingly - the worst kind of failure, because retrieval would still return four chunks
 * and the answer would still read fluently. The model and the width are stored next to the
 * hash so this is detectable rather than assumed.
 *
 * <p><strong>Indexing never blocks or fails startup.</strong> It runs after the application is
 * ready, on one thread it owns, and any failure is a WARN. Chat keeps working against whatever
 * is already indexed, including nothing: a question with no retrieved chunks is answered from
 * the report alone, which is a worse answer rather than an error.
 *
 * <p>Counts only in the logs - pages scanned, skipped, embedded, chunks written, and slugs.
 * Never a chunk's text ({@code CLAUDE.md} rule 19).
 */
@Component
public class ContentIndexer {

	private static final Logger log = LoggerFactory.getLogger(ContentIndexer.class);

	private final BiomarkerPageLoader loader;
	private final PageChunker chunker;
	private final ContentChunkRepository repository;
	private final EmbeddingGateway embeddings;
	private final boolean requireReviewed;
	private final boolean indexOnStartupEnabled;

	public ContentIndexer(BiomarkerPageLoader loader, PageChunker chunker,
			ContentChunkRepository repository, EmbeddingGateway embeddings,
			MediScanProperties properties) {

		this.loader = loader;
		this.chunker = chunker;
		this.repository = repository;
		this.embeddings = embeddings;
		this.requireReviewed = properties.rag().requireReviewed();
		this.indexOnStartupEnabled = properties.rag().indexOnStartup();
	}

	/**
	 * Indexes in the background once the application is up.
	 *
	 * <p>A thread of its own rather than a shared pool: the job pool has two workers and a 2 GB
	 * box, and a first-run index is a few seconds of network waiting that should not occupy a
	 * worker a report could use. The thread exits when indexing finishes.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void indexOnStartup() {
		if (!indexOnStartupEnabled) {
			// Off under the test profile. Embedding takes from the daily cap, so indexing at
			// startup would spend budget that every test asserting on the cap then measures -
			// and the indexing tests drive the indexer directly, which is faster and exact.
			log.debug("Startup content indexing is disabled");
			return;
		}
		Thread.ofVirtual().name("content-indexer").start(this::indexQuietly);
	}

	/** Runs an index pass, turning any failure into a WARN. */
	public void indexQuietly() {
		try {
			Result result = index();
			log.info("Content index: scanned={} embedded={} unchanged={} skippedUnreviewed={} "
					+ "removed={} chunksWritten={} chunksTotal={}",
					result.scanned(), result.embedded(), result.unchanged(),
					result.skippedUnreviewed(), result.removed(), result.chunksWritten(),
					repository.chunkCount());
		}
		catch (RuntimeException ex) {
			// Quota, network, a malformed page. Chat still answers from whatever is indexed,
			// and the next start tries again.
			log.warn("Content indexing failed; keeping the existing index. cause={} detail={}",
					ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	/**
	 * One index pass.
	 *
	 * @return what it did, for the log line and for the tests that assert a restart embeds
	 *     nothing
	 */
	public Result index() {
		List<BiomarkerPage> pages = loader.loadAll();
		Map<String, ContentChunkRepository.IndexedPage> alreadyIndexed = repository.indexed();

		String model = embeddings.model();
		int width = embeddings.dimensions();

		int embedded = 0;
		int unchanged = 0;
		int skippedUnreviewed = 0;
		int chunksWritten = 0;
		Set<String> seen = new HashSet<>();

		for (BiomarkerPage page : pages) {
			if (requireReviewed && !page.isReviewed()) {
				// Unreviewed medical text must not reach a live answer (LLD C5). Not indexed
				// at all, rather than indexed and filtered at query time - a filter is
				// something that can be forgotten.
				skippedUnreviewed++;
				continue;
			}
			seen.add(page.slug());

			ContentChunkRepository.IndexedPage existing = alreadyIndexed.get(page.slug());
			if (existing != null && existing.matches(page, model, width)) {
				unchanged++;
				continue;
			}

			List<PageChunk> chunks = chunker.chunk(page);
			if (chunks.isEmpty()) {
				log.warn("Biomarker page {} produced no chunks and was not indexed", page.slug());
				continue;
			}

			List<String> texts = new ArrayList<>(chunks.size());
			chunks.forEach(chunk -> texts.add(chunk.text()));

			repository.replacePage(page, chunks, embeddings.embedDocuments(texts), model, width);

			embedded++;
			chunksWritten += chunks.size();
			log.debug("Embedded page slug={} chunks={}", page.slug(), chunks.size());
		}

		int removed = forgetPagesThatAreGone(alreadyIndexed.keySet(), seen);

		return new Result(pages.size(), embedded, unchanged, skippedUnreviewed, removed,
				chunksWritten);
	}

	/**
	 * Drops chunks for pages that are no longer bundled, or no longer qualify.
	 *
	 * <p>A leftover page stays retrievable, so chat would cite a page the reader cannot open -
	 * and if {@code require-reviewed} was turned on, it would go on answering from content that
	 * was just declared unreviewed.
	 */
	private int forgetPagesThatAreGone(Set<String> indexedSlugs, Set<String> currentSlugs) {
		int removed = 0;
		for (String slug : indexedSlugs) {
			if (!currentSlugs.contains(slug)) {
				repository.forget(slug);
				removed++;
				log.info("Removed biomarker page from the index: slug={}", slug);
			}
		}
		return removed;
	}

	/**
	 * What one pass did.
	 *
	 * @param scanned pages found on the classpath
	 * @param embedded pages that cost embedding calls
	 * @param unchanged pages whose hash, model and width all matched, so cost nothing
	 * @param skippedUnreviewed pages left out because nobody has reviewed them
	 * @param removed pages dropped because they are no longer bundled or no longer qualify
	 * @param chunksWritten rows inserted
	 */
	public record Result(int scanned, int embedded, int unchanged, int skippedUnreviewed,
			int removed, int chunksWritten) {
	}
}
