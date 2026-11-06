package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The backend's copy of the biomarker pages must match the client's ({@code LLD} 5, C1).
 *
 * <p>{@code client/content/biomarkers/} is the source of truth ({@code CLAUDE.md} rule 20).
 * The backend carries a copy because the Docker build context is {@code backend/} alone - a
 * page outside it does not exist at image build time, so an un-synced page is one the deployed
 * app cannot answer from. Same arrangement as {@code index.json}, same reason.
 *
 * <p><strong>The failure this exists to catch</strong> is quiet rather than loud. A page edited
 * on the client and not synced leaves the backend embedding the old text: chat keeps answering,
 * confidently, from content nobody reviewed in that form. Nothing else in the system would
 * notice - the index hash matches its own stale copy, so even re-indexing is a no-op. That is
 * why this compares content and not just filenames.
 *
 * <p>Pure file comparison, no Spring context and no database, so it runs in every
 * {@code ./mvnw verify} at no cost.
 */
class BiomarkerContentSyncTest {

	private static final Path CLIENT_DIR =
			Paths.get("..", "client", "content", "biomarkers");

	private static final String BACKEND_DIR = "biomarkers/pages/";

	private static final String FIX = "run scripts/sync-biomarker-content.sh";

	/** {@code slug: ldl-cholesterol} on its own line in the frontmatter. */
	private static final Pattern FRONTMATTER_SLUG =
			Pattern.compile("^slug:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

	private static final Pattern INDEX_SLUG =
			Pattern.compile("\"slug\"\\s*:\\s*\"([^\"]+)\"");

	@Test
	@DisplayName("every client page has a backend copy with the same content")
	void pagesMatch() throws IOException {
		Map<String, String> client = clientPages();

		assertThat(client)
				.as("the client pages are the source of truth and must exist")
				.isNotEmpty();

		for (Map.Entry<String, String> page : client.entrySet()) {
			String name = page.getKey();
			ClassPathResource copy = new ClassPathResource(BACKEND_DIR + name);

			assertThat(copy.exists())
					.as("backend copy of %s is missing; %s", name, FIX)
					.isTrue();

			assertThat(read(copy))
					.as("backend copy of %s differs from the client page; %s", name, FIX)
					.isEqualTo(page.getValue());
		}
	}

	/**
	 * A page deleted on the client must disappear from the backend too.
	 *
	 * <p>Not symmetry for its own sake: a leftover page stays in the RAG index and can still be
	 * retrieved, so chat would cite a page that no longer exists for the reader to open. The
	 * sync script removes the target directory rather than copying over it for this reason.
	 */
	@Test
	@DisplayName("the backend has no page the client does not have")
	void noOrphanedBackendPages() throws IOException {
		assertThat(backendPageNames())
				.as("backend has pages the client does not; %s", FIX)
				.containsExactlyInAnyOrderElementsOf(clientPages().keySet());
	}

	/**
	 * One page per slug in the index, no more and no fewer (7b LLD 7.1).
	 *
	 * <p>Checked on the backend's own copies, because those are what the indexer reads. A slug
	 * in the index with no page is a biomarker the results table links to and chat cannot
	 * retrieve; a page with no slug is content nothing can reach.
	 */
	@Test
	@DisplayName("backend pages and the backend index cover exactly the same slugs")
	void slugsAgreeWithIndex() throws IOException {
		assertThat(backendPageSlugs())
				.as("backend pages and index.json disagree on slugs; %s", FIX)
				.isEqualTo(indexSlugs());
	}

	/** Every page's frontmatter slug matches its filename, which is what the loader assumes. */
	@Test
	@DisplayName("each page's frontmatter slug matches its filename")
	void frontmatterSlugMatchesFilename() throws IOException {
		for (String name : backendPageNames()) {
			String slug = name.substring(0, name.length() - ".md".length());
			assertThat(slugOf(read(new ClassPathResource(BACKEND_DIR + name))))
					.as("frontmatter slug in %s does not match its filename", name)
					.isEqualTo(slug);
		}
	}

	// --- helpers ------------------------------------------------------------

	private static Map<String, String> clientPages() throws IOException {
		assertThat(CLIENT_DIR).as("the client content directory must exist").exists();

		Map<String, String> pages = new TreeMap<>();
		try (Stream<Path> files = Files.list(CLIENT_DIR)) {
			files.filter(path -> path.getFileName().toString().endsWith(".md"))
					.forEach(path -> pages.put(path.getFileName().toString(), readFile(path)));
		}
		return pages;
	}

	/**
	 * Backend page filenames, read from the classpath directory.
	 *
	 * <p>Listed through the resource's file, not a hardcoded list - the point of the test is to
	 * notice a file that should not be there.
	 */
	private static List<String> backendPageNames() throws IOException {
		ClassPathResource dir = new ClassPathResource(BACKEND_DIR);
		assertThat(dir.exists()).as("backend pages directory is missing; %s", FIX).isTrue();

		try (Stream<Path> files = Files.list(dir.getFile().toPath())) {
			return files.map(path -> path.getFileName().toString())
					.filter(name -> name.endsWith(".md"))
					.sorted()
					.toList();
		}
	}

	private static TreeSet<String> backendPageSlugs() throws IOException {
		TreeSet<String> slugs = new TreeSet<>();
		for (String name : backendPageNames()) {
			slugs.add(name.substring(0, name.length() - ".md".length()));
		}
		return slugs;
	}

	private static TreeSet<String> indexSlugs() throws IOException {
		String index = read(new ClassPathResource("biomarkers/index.json"));
		TreeSet<String> slugs = new TreeSet<>();
		Matcher matcher = INDEX_SLUG.matcher(index);
		while (matcher.find()) {
			slugs.add(matcher.group(1));
		}
		assertThat(slugs).as("the backend index lists no slugs").isNotEmpty();
		return slugs;
	}

	private static String slugOf(String page) {
		Matcher matcher = FRONTMATTER_SLUG.matcher(page);
		assertThat(matcher.find()).as("page has no `slug:` line in its frontmatter").isTrue();
		return matcher.group(1);
	}

	/**
	 * Reads a page with line endings normalised.
	 *
	 * <p>Deliberately not a byte comparison, though {@code LLD} 5 words it that way. Git's
	 * {@code core.autocrlf} rewrites line endings on checkout, so on Windows the two working
	 * copies of an identical file genuinely differ byte for byte and a strict comparison would
	 * fail for every developer on that platform and nobody on Linux. Normalising compares what
	 * the rule is actually about - the text that gets embedded - and still fails on any real
	 * edit.
	 */
	private static String read(ClassPathResource resource) throws IOException {
		try (InputStream in = resource.getInputStream()) {
			return normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static String readFile(Path path) {
		try {
			return normalize(Files.readString(path, StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String normalize(String text) {
		return text.replace("\r\n", "\n").strip();
	}
}
