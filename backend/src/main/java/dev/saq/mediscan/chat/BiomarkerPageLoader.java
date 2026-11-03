package dev.saq.mediscan.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads the curated biomarker pages off the classpath ({@code LLD} 5).
 *
 * <p>The files are the backend's synced copy of {@code client/content/biomarkers/}, so the
 * frontmatter schema is 7b LLD 7.1's and the client is the one that defines it. Only the fields
 * indexing and retrieval need are read: a field this class ignores cannot drift from the
 * client's rendering of it.
 *
 * <p><strong>A malformed page fails loudly, with its filename.</strong> Skipping it quietly
 * would leave a biomarker missing from the index, which surfaces much later and much more
 * confusingly as chat not knowing about a test the results table links to happily.
 *
 * <p><strong>The hash is of the whole file.</strong> Frontmatter and body together, because a
 * title change alters what every chunk of that page says about itself and therefore what it
 * embeds to. Hashing only the body would leave those chunks stale while looking current.
 */
@Component
public class BiomarkerPageLoader {

	private static final String PATTERN = "classpath*:biomarkers/pages/*.md";

	private static final String DELIMITER = "---";

	/** Parsed, but only to be recognised as "not reviewed". */
	private static final String NULL_LITERAL = "null";

	/**
	 * Every page, sorted by slug.
	 *
	 * <p>Sorted so indexing order is deterministic, which makes a log of counts comparable
	 * between runs and a partial failure reproducible.
	 *
	 * @throws IllegalStateException if any page is malformed, naming the file
	 */
	public List<BiomarkerPage> loadAll() {
		List<BiomarkerPage> pages = new ArrayList<>();

		for (Resource resource : resources()) {
			String name = resource.getFilename();
			if (name == null) {
				continue;
			}
			pages.add(parse(name, read(resource)));
		}

		pages.sort(Comparator.comparing(BiomarkerPage::slug));
		return List.copyOf(pages);
	}

	/**
	 * Parses one page.
	 *
	 * @param filename used only in error messages, so a failure says which file to open
	 * @throws IllegalStateException when the frontmatter is missing or incomplete
	 */
	public BiomarkerPage parse(String filename, String content) {
		String normalized = content.replace("\r\n", "\n");

		if (!normalized.startsWith(DELIMITER + "\n")) {
			throw malformed(filename, "it does not start with a `---` frontmatter block");
		}
		int end = normalized.indexOf("\n" + DELIMITER, DELIMITER.length());
		if (end < 0) {
			throw malformed(filename, "its frontmatter block is not closed with `---`");
		}

		String frontmatter = normalized.substring(DELIMITER.length() + 1, end + 1);
		String body = normalized.substring(end + 1 + DELIMITER.length()).strip();

		Map<String, Object> fields = parseYaml(filename, frontmatter);

		return new BiomarkerPage(
				required(filename, fields, "slug"),
				required(filename, fields, "title"),
				required(filename, fields, "panel"),
				aliases(fields),
				required(filename, fields, "measures"),
				reviewedOn(filename, fields),
				body,
				sha256(normalized));
	}

	/**
	 * Parses the frontmatter with a constructor that can only produce plain values.
	 *
	 * <p>SnakeYAML's default {@code Yaml()} instantiates whatever class a {@code !!} tag names,
	 * which turns any YAML document into a deserialisation gadget. These files are ours and
	 * come from the image, so this is defence in depth rather than a live hole - but the cost
	 * of using the safe constructor is one argument, and the cost of not using it arrives the
	 * day the input stops being ours.
	 */
	private static Map<String, Object> parseYaml(String filename, String frontmatter) {
		try {
			Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
			Object parsed = yaml.load(frontmatter);
			if (!(parsed instanceof Map<?, ?> map)) {
				throw malformed(filename, "its frontmatter is not a set of fields");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> fields = (Map<String, Object>) map;
			return fields;
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// The parser's own message can quote the document, which is fine here - this is
			// our own reference content, not report data - but the filename is what a reader
			// needs first.
			throw malformed(filename, "its frontmatter is not valid YAML");
		}
	}

	private static String required(String filename, Map<String, Object> fields, String key) {
		Object value = fields.get(key);
		if (value == null || value.toString().isBlank()) {
			throw malformed(filename, "it has no `" + key + "`");
		}
		return value.toString().strip();
	}

	private static List<String> aliases(Map<String, Object> fields) {
		Object value = fields.get("aliases");
		if (!(value instanceof List<?> list)) {
			// Optional. A test with no other common name is normal, not an error.
			return List.of();
		}
		return list.stream().map(String::valueOf).map(String::strip).toList();
	}

	/**
	 * The review date, or null.
	 *
	 * <p>Null is the ordinary case and must stay cheap to express: nothing in this app marks a
	 * page reviewed, a person does, and until then the page is simply not reviewed. An
	 * unparseable date is an error rather than a silent null, because quietly turning a typo
	 * into "not reviewed" would hide a page from production for a reason nobody could see.
	 */
	private static LocalDate reviewedOn(String filename, Map<String, Object> fields) {
		Object value = fields.get("reviewedOn");
		if (value == null || NULL_LITERAL.equalsIgnoreCase(value.toString().strip())) {
			return null;
		}
		if (value instanceof java.util.Date date) {
			// SnakeYAML resolves an unquoted YYYY-MM-DD to a Date before we see it.
			return date.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
		}
		try {
			return LocalDate.parse(value.toString().strip());
		}
		catch (DateTimeParseException ex) {
			throw malformed(filename, "its `reviewedOn` is not a YYYY-MM-DD date or null");
		}
	}

	private Resource[] resources() {
		try {
			Resource[] found = new PathMatchingResourcePatternResolver().getResources(PATTERN);
			if (found.length == 0) {
				throw new IllegalStateException(
						"no biomarker pages on the classpath at " + PATTERN
								+ "; run scripts/sync-biomarker-content.sh");
			}
			return found;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not list biomarker pages", ex);
		}
	}

	private static String read(Resource resource) {
		try (InputStream in = resource.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not read " + resource.getFilename(), ex);
		}
	}

	/**
	 * SHA-256 of the normalised file, hex encoded.
	 *
	 * <p>Normalised line endings on purpose, matching {@code BiomarkerContentSyncTest}: Git
	 * rewrites them on checkout, and a hash that changed with the platform would re-embed every
	 * page the first time the project was built on Windows.
	 */
	private static String sha256(String content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every JVM", ex);
		}
	}

	private static IllegalStateException malformed(String filename, String problem) {
		return new IllegalStateException("biomarker page " + filename + " is malformed: " + problem);
	}
}
