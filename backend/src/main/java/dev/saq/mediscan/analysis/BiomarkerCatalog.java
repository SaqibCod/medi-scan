package dev.saq.mediscan.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps an extracted test name to a curated biomarker page.
 *
 * <p>The slug is what lets the results view link a row to its explanation, which is most of
 * what makes the app useful rather than a table viewer. Matching is by normalised name against
 * the display name and every alias, because reports print the same marker a dozen ways -
 * {@code HDL}, {@code HDL-C}, {@code Cholesterol, HDL}.
 *
 * <p><strong>No match is a normal outcome.</strong> The slug is simply {@code null} and the row
 * is kept and displayed; it just does not link anywhere. Guessing at a near-match would point
 * a reader at an explanation of a different test, which is worse than no link.
 *
 * <p>The index is a copy of {@code client/content/biomarkers/index.json}, kept in step by
 * {@code scripts/sync-biomarker-index.sh} and guarded by {@code BiomarkerIndexSyncTest}. The
 * client copy is the source of truth because the curated pages live beside it.
 */
@Component
public class BiomarkerCatalog {

	private static final Logger log = LoggerFactory.getLogger(BiomarkerCatalog.class);

	private static final String INDEX = "biomarkers/index.json";

	/** Normalised name to slug, covering display names and aliases alike. */
	private final Map<String, String> slugsByNormalizedName;

	private final List<Entry> entries;

	public BiomarkerCatalog(JsonMapper jsonMapper) {
		this.entries = readIndex(jsonMapper);
		this.slugsByNormalizedName = index(entries);

		log.info("Loaded {} biomarkers with {} name variants", entries.size(),
				slugsByNormalizedName.size());
	}

	/**
	 * The slug for {@code testName}, or empty when nothing matches.
	 *
	 * @param testName the name as printed on the report
	 */
	public Optional<String> findSlug(String testName) {
		String normalized = TestNameNormalizer.normalize(testName);
		if (normalized.isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(slugsByNormalizedName.get(normalized));
	}

	/** Every catalogued biomarker, for tests and for the sync check. */
	public List<Entry> entries() {
		return entries;
	}

	public int size() {
		return entries.size();
	}

	private static Map<String, String> index(List<Entry> entries) {
		Map<String, String> byName = new HashMap<>();

		for (Entry entry : entries) {
			// The display name is itself a matchable variant, so a report printing the
			// canonical name needs no alias for it.
			put(byName, entry.displayName(), entry.slug());
			// And the slug, so "free-t4" on a report resolves too.
			put(byName, entry.slug(), entry.slug());

			for (String alias : entry.aliases()) {
				put(byName, alias, entry.slug());
			}
		}
		return Map.copyOf(byName);
	}

	private static void put(Map<String, String> byName, String name, String slug) {
		String normalized = TestNameNormalizer.normalize(name);
		if (normalized.isEmpty()) {
			return;
		}
		String existing = byName.put(normalized, slug);
		if (existing != null && !existing.equals(slug)) {
			// Two biomarkers claiming one name would make the slug depend on map iteration
			// order, so a reader could get either page. A startup failure instead.
			throw new IllegalStateException("alias '" + name + "' in " + INDEX
					+ " maps to both '" + existing + "' and '" + slug + "'");
		}
	}

	private static List<Entry> readIndex(JsonMapper jsonMapper) {
		ClassPathResource resource = new ClassPathResource(INDEX);
		if (!resource.exists()) {
			throw new IllegalStateException("missing " + INDEX
					+ " on the classpath; run scripts/sync-biomarker-index.sh");
		}

		try (InputStream in = resource.getInputStream()) {
			List<Entry> loaded = jsonMapper.readValue(in, new TypeReference<List<Entry>>() {
			});
			if (loaded == null || loaded.isEmpty()) {
				throw new IllegalStateException(INDEX + " lists no biomarkers");
			}
			loaded.forEach(Entry::validate);
			return List.copyOf(loaded);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + INDEX, ex);
		}
	}

	/**
	 * One biomarker in the index.
	 *
	 * @param slug the filename of the curated page, and the value stored on the row
	 * @param displayName the canonical name
	 * @param aliases names as they appear on real reports
	 */
	public record Entry(String slug, String displayName, List<String> aliases) {

		public Entry {
			aliases = aliases == null ? List.of() : List.copyOf(aliases);
		}

		void validate() {
			if (slug == null || slug.isBlank()) {
				throw new IllegalStateException("a biomarker in " + INDEX + " has no slug");
			}
			if (displayName == null || displayName.isBlank()) {
				throw new IllegalStateException(
						"biomarker '" + slug + "' in " + INDEX + " has no displayName");
			}
			if (!slug.matches("[a-z0-9-]+")) {
				// The slug becomes a URL path segment on the client.
				throw new IllegalStateException("biomarker slug '" + slug + "' in " + INDEX
						+ " must be lowercase letters, digits and hyphens");
			}
		}
	}
}
