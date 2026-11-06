package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reading the curated pages off the classpath ({@code LLD} 5).
 *
 * <p>Two jobs. The first is parsing the frontmatter the client already validates (7b LLD 7.1),
 * so the backend and the browser agree on what a page says about itself. The second is the
 * content hash, which is what makes a restart free: {@code content_index} stores it, and a page
 * whose hash is unchanged is never embedded again.
 *
 * <p>A malformed page fails loudly with its own filename. Skipping it quietly would mean a
 * biomarker silently missing from the index, which shows up much later as chat not knowing
 * about a test the results table happily links to.
 */
class BiomarkerPageLoaderTest {

	private final BiomarkerPageLoader loader = new BiomarkerPageLoader();

	@Nested
	@DisplayName("the real bundled pages")
	class RealPages {

		@Test
		@DisplayName("every synced page loads")
		void allPagesLoad() {
			List<BiomarkerPage> pages = loader.loadAll();

			// 21 pages are synced from the client. The exact count is asserted by
			// BiomarkerContentSyncTest against index.json; here it only has to be plural.
			assertThat(pages).hasSizeGreaterThan(10);
			assertThat(pages).extracting(BiomarkerPage::slug).doesNotHaveDuplicates();
		}

		@Test
		@DisplayName("a known page parses its frontmatter and body")
		void parsesAKnownPage() {
			BiomarkerPage page = load("ldl-cholesterol");

			assertThat(page.title()).isEqualTo("LDL Cholesterol");
			assertThat(page.panel()).isEqualTo("Lipid panel");
			assertThat(page.aliases()).contains("LDL");
			assertThat(page.measures()).isNotBlank();
			assertThat(page.body()).contains("## What it measures");
			// The frontmatter must not reach the body: it would be embedded as if it were
			// content, and "reviewedOn: null" is not something to retrieve.
			assertThat(page.body()).doesNotContain("slug:").doesNotContain("---");
		}

		@Test
		@DisplayName("no page is marked reviewed yet")
		void nothingIsReviewedYet() {
			// Phase 5 does not review content and must not mark any page reviewed. If this
			// ever fails, someone edited a page's frontmatter rather than reviewing it.
			assertThat(loader.loadAll()).allSatisfy(page ->
					assertThat(page.isReviewed()).isFalse());
		}

		@Test
		@DisplayName("the hash is stable across loads and differs between pages")
		void hashIsStableAndDistinct() {
			// Stability is the whole point: an unstable hash would re-embed everything on
			// every restart and spend the daily cap doing nothing.
			assertThat(load("tsh").contentHash()).isEqualTo(load("tsh").contentHash());
			assertThat(load("tsh").contentHash()).isNotEqualTo(load("glucose").contentHash());
			assertThat(load("tsh").contentHash()).hasSize(64);
		}

		private BiomarkerPage load(String slug) {
			return loader.loadAll().stream()
					.filter(page -> page.slug().equals(slug))
					.findFirst()
					.orElseThrow(() -> new AssertionError("no page " + slug));
		}
	}

	@Nested
	@DisplayName("parsing one file")
	class Parsing {

		@Test
		@DisplayName("reviewedOn becomes a date when a person has filled it in")
		void reviewedOnParses() {
			BiomarkerPage page = loader.parse("tsh.md", """
					---
					slug: tsh
					title: TSH
					panel: Thyroid
					aliases: [Thyrotropin]
					measures: A hormone
					reviewedOn: 2026-07-01
					reviewer: A Person
					---

					## What it measures

					Something.
					""");

			assertThat(page.reviewedOn()).isEqualTo(LocalDate.of(2026, 7, 1));
			assertThat(page.isReviewed()).isTrue();
		}

		@Test
		@DisplayName("a page with no aliases is fine")
		void aliasesAreOptional() {
			assertThat(loader.parse("x.md", """
					---
					slug: x
					title: X
					panel: P
					measures: M
					reviewedOn: null
					---

					## What it measures

					Something.
					""").aliases()).isEmpty();
		}

		@Test
		@DisplayName("a missing required field names the file")
		void missingFieldNamesTheFile() {
			assertThatThrownBy(() -> loader.parse("broken.md", """
					---
					slug: broken
					panel: P
					measures: M
					---

					## What it measures

					Something.
					"""))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("broken.md")
					.hasMessageContaining("title");
		}

		@Test
		@DisplayName("a file with no frontmatter names the file")
		void missingFrontmatterNamesTheFile() {
			assertThatThrownBy(() -> loader.parse("naked.md", "## What it measures\n\nText.\n"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("naked.md");
		}

		@Test
		@DisplayName("the frontmatter cannot construct arbitrary objects")
		void yamlIsParsedSafely() {
			// SnakeYAML's default constructor instantiates classes named by a tag. These files
			// are ours, but a parser that can build arbitrary objects from a document is a
			// deserialisation gadget waiting for the day the input stops being ours.
			assertThatThrownBy(() -> loader.parse("evil.md", """
					---
					slug: !!java.net.URL [http://example.com]
					title: X
					panel: P
					measures: M
					---

					## What it measures

					Text.
					"""))
					.isInstanceOf(Exception.class);
		}

		@Test
		@DisplayName("a page never prints its body")
		void pageHidesItsBody() {
			BiomarkerPage page = loader.parse("x.md", """
					---
					slug: x
					title: X
					panel: P
					measures: M
					---

					## What it measures

					Distinctive body sentence.
					""");

			assertThat(page.toString())
					.doesNotContain("Distinctive body sentence")
					.contains("x");
		}
	}
}
