package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The backend's biomarker index must match the client's ({@code LLD} 11.7).
 *
 * <p>The client copy is the source of truth: the curated markdown pages sit beside it, and
 * from phase 5 the same folder feeds the RAG index. The backend needs its own copy on the
 * classpath so a slug lookup costs no network call.
 *
 * <p>Two copies of anything drift. The failure that matters is a slug added to the client
 * index with a page, which the backend then never assigns - so the row renders without a link
 * to the page that exists. This test turns that into a build failure with the fix in the
 * message.
 */
class BiomarkerIndexSyncTest {

	private static final Path CLIENT_INDEX =
			Paths.get("..", "client", "content", "biomarkers", "index.json");

	@Test
	@DisplayName("the two index files are byte-for-byte identical")
	void indexesMatch() throws IOException {
		assertThat(CLIENT_INDEX)
				.as("the client index is the source of truth and must exist")
				.exists();

		String client = normalize(Files.readString(CLIENT_INDEX, StandardCharsets.UTF_8));
		String backend = normalize(readBackendIndex());

		assertThat(backend)
				.as("backend and client biomarker indexes differ; "
						+ "run scripts/sync-biomarker-index.sh to copy the client file over")
				.isEqualTo(client);
	}

	@Test
	@DisplayName("every slug in the index is a valid URL path segment")
	void slugsAreUrlSafe() throws IOException {
		// The slug becomes a path segment on the client and the filename of the curated page.
		String backend = readBackendIndex();

		assertThat(backend).contains("\"slug\"");
		java.util.regex.Matcher matcher =
				java.util.regex.Pattern.compile("\"slug\"\\s*:\\s*\"([^\"]+)\"").matcher(backend);

		int found = 0;
		while (matcher.find()) {
			found++;
			assertThat(matcher.group(1)).matches("[a-z0-9-]+");
		}
		assertThat(found).isPositive();
	}

	@Test
	@DisplayName("a curated page exists for the slug the samples rely on")
	void curatedPageExistsForTsh() {
		// Only one page is written so far. This asserts the naming contract between the index
		// and the content folder, so the first real batch of pages has something to follow.
		assertThat(Paths.get("..", "client", "content", "biomarkers", "tsh.md")).exists();
	}

	/** Ignores line-ending and trailing-whitespace differences, which Git introduces. */
	private static String normalize(String json) {
		return json.replace("\r\n", "\n").strip();
	}

	private static String readBackendIndex() throws IOException {
		ClassPathResource resource = new ClassPathResource("biomarkers/index.json");
		assertThat(resource.exists())
				.as("backend biomarker index is missing; run scripts/sync-biomarker-index.sh")
				.isTrue();

		try (InputStream in = resource.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
