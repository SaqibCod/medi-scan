package dev.saq.mediscan.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Finds the biomarker page sections worth putting in the prompt ({@code LLD} 6.2).
 *
 * <p>Similarity search, then one correction. The correction exists because a question like
 * "why is my LDL flagged?" is mostly about <em>this report</em> and only a little about LDL in
 * general - so its embedding can sit closer to a page about flags than to the LDL page. If the
 * question names a marker that is on the reader's report and that marker has a curated page,
 * that page's opening section is included whether or not the search found it.
 *
 * <p><strong>Retrieving nothing is a valid outcome.</strong> Below the similarity threshold the
 * answer rests on the report alone, which is a better answer than one padded with a page about
 * a different test. The prompt handles an empty {@code <reference>} block.
 *
 * <p>Logged: how many chunks, the top similarity to two decimals, and the slugs. A slug is
 * reference content and may be logged; a chunk's text may not ({@code CLAUDE.md} rule 19).
 */
@Component
public class ChunkRetriever {

	private static final Logger log = LoggerFactory.getLogger(ChunkRetriever.class);

	private final EmbeddingGateway embeddings;
	private final ContentChunkRepository repository;
	private final BiomarkerPageLoader loader;
	private final int topK;
	private final double minSimilarity;

	/** slug and alias, lowercased, to the slug they name. Built once: the pages do not change. */
	private volatile Map<String, String> namesToSlugs;

	public ChunkRetriever(EmbeddingGateway embeddings, ContentChunkRepository repository,
			BiomarkerPageLoader loader, MediScanProperties properties) {

		this.embeddings = embeddings;
		this.repository = repository;
		this.loader = loader;
		this.topK = properties.rag().topK();
		this.minSimilarity = properties.rag().minSimilarity();
	}

	/**
	 * The chunks to put in {@code <reference>}, best first.
	 *
	 * @param maskedQuestion the question, already masked - this is embedded, so it must not be
	 *     the raw text
	 */
	public List<PageChunk> retrieve(String maskedQuestion, ChatReport report) {
		List<ScoredChunk> found =
				repository.search(embeddings.embedQuery(maskedQuestion), topK, minSimilarity);

		List<PageChunk> chunks = new ArrayList<>(found.stream().map(ScoredChunk::chunk).toList());
		boostNamedMarker(maskedQuestion, report, chunks);

		log.debug("chat retrieval chunks={} topSimilarity={} slugs={}",
				chunks.size(),
				found.isEmpty() ? 0.0 : found.getFirst().roundedSimilarity(),
				chunks.stream().map(PageChunk::slug).distinct().toList());

		return List.copyOf(chunks);
	}

	/**
	 * Makes sure a marker the question names by its own report name is represented.
	 *
	 * <p>Only markers on <em>this</em> report, so the boost cannot introduce a page about a
	 * test the reader never had. Replaces the weakest chunk rather than growing the prompt,
	 * because {@code rag.top-k} is the budget and a longer prompt is a slower, costlier answer.
	 */
	private void boostNamedMarker(String maskedQuestion, ChatReport report,
			List<PageChunk> chunks) {

		String haystack = " " + maskedQuestion.toLowerCase(Locale.ROOT) + " ";

		for (String slug : namedSlugs(haystack, report)) {
			if (chunks.stream().anyMatch(chunk -> chunk.slug().equals(slug))) {
				continue;
			}
			repository.firstChunkOf(slug).ifPresent(chunk -> {
				if (chunks.size() >= topK) {
					chunks.removeLast();
				}
				// At the front: the reader named this test, so it is the most relevant thing
				// retrieved whatever the vector arithmetic decided.
				chunks.addFirst(chunk);
			});
			// One boost per question. Two would crowd out everything the search found.
			return;
		}
	}

	/** Slugs on this report whose name or an alias appears in the question. */
	private List<String> namedSlugs(String haystack, ChatReport report) {
		Map<String, String> index = nameIndex();
		List<String> named = new ArrayList<>();

		for (ChatReport.ChatBiomarker biomarker : report.biomarkers()) {
			String slug = biomarker.slug();
			if (slug == null || slug.isBlank()) {
				continue;
			}
			if (mentions(haystack, biomarker.testName()) || mentionsAnyNameOf(haystack, index, slug)) {
				named.add(slug);
			}
		}
		return named;
	}

	private static boolean mentionsAnyNameOf(String haystack, Map<String, String> index,
			String slug) {

		return index.entrySet().stream()
				.filter(entry -> entry.getValue().equals(slug))
				.anyMatch(entry -> mentions(haystack, entry.getKey()));
	}

	/**
	 * Whether {@code name} appears as a whole word.
	 *
	 * <p>Padded rather than a regex: "LDL" must not match inside "VLDL", and building a pattern
	 * per alias per question would be the most expensive thing on this path.
	 */
	private static boolean mentions(String haystack, String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		String needle = " " + name.toLowerCase(Locale.ROOT).strip() + " ";
		if (haystack.contains(needle)) {
			return true;
		}
		// Also as the last word before punctuation: "why is my LDL?" - the padded form would
		// miss it, and that is the single most likely question on the report page.
		for (String suffix : List.of("?", ".", ",", "!", ":", ";")) {
			if (haystack.contains(" " + name.toLowerCase(Locale.ROOT).strip() + suffix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Builds the alias index once.
	 *
	 * <p>The pages are read from the classpath and never change while the process runs, so
	 * rebuilding this per question would read and parse twenty-one files to answer one. Lazily
	 * rather than in the constructor, so a malformed page fails the first chat rather than
	 * startup - chat degrading is better than the server not booting.
	 */
	private Map<String, String> nameIndex() {
		Map<String, String> index = namesToSlugs;
		if (index != null) {
			return index;
		}
		Map<String, String> built = new LinkedHashMap<>();
		for (BiomarkerPage page : loader.loadAll()) {
			built.put(page.title().toLowerCase(Locale.ROOT), page.slug());
			built.put(page.slug().toLowerCase(Locale.ROOT).replace('-', ' '), page.slug());
			for (String alias : page.aliases()) {
				built.put(alias.toLowerCase(Locale.ROOT), page.slug());
			}
		}
		namesToSlugs = Map.copyOf(built);
		return namesToSlugs;
	}
}
