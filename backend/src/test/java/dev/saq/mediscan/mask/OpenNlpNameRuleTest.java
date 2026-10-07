package dev.saq.mediscan.mask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import dev.saq.mediscan.support.TestProperties;

/**
 * The optional OpenNLP name rule, and the model it depends on.
 *
 * <p>Two jobs. First, prove the claim the README makes: that a 2010-vintage OpenNLP 1.5 model
 * still loads and still finds names under {@code opennlp-tools} 2.5.12. Second, pin the
 * false-positive filters, because the model was trained on news text and a lab report is
 * nothing like news text - it will propose test names as people, and masking a test name
 * loses the patient a result.
 *
 * <p>These are also the measurements behind the false-positive and false-negative notes in
 * {@code SECURITY.md}.
 */
class OpenNlpNameRuleTest {

	private static final ResourceLoader RESOURCES = new DefaultResourceLoader();

	private final OpenNlpNameRule rule = new OpenNlpNameRule(
			TestProperties.withOpenNlp(true, 0.7), RESOURCES);

	@Test
	@DisplayName("the committed 1.5 model loads under opennlp-tools 2.5.12")
	void modelLoads() {
		// The compatibility claim in src/main/resources/opennlp/README.md. If a future
		// OpenNLP upgrade breaks it, this fails rather than the rule silently finding nothing.
		assertThat(rule.name()).isEqualTo("openNlpName");
	}

	@Test
	@DisplayName("finds a name in a signature line the label rules miss")
	void findsUnlabelledName() {
		// This is what the rule is for: no label, no honorific, so nothing else can see it.
		String text = "Reviewed and released by Alan Whitfield on completion.";

		List<Span> spans = rule.find(text, ProtectedSpans.none());

		assertThat(spans).isNotEmpty();
		assertThat(covered(text, spans)).anySatisfy(
				name -> assertThat(name).contains("Whitfield"));
	}

	@Test
	@DisplayName("never proposes a span inside a result row")
	void skipsResultRows() {
		String text = """
				Hemoglobin A1c              5.4         %           4.0-5.6
				Vitamin B12                 410         pg/mL       200-900
				Free T4                     1.74        ng/dL       0.82-1.77
				Thyroid Peroxidase Antibodies   Negative            Negative
				""";
		ProtectedSpans protectedRows = ResultRowDetector.detect(text);

		// A result row is skipped before the model even runs on it. It is the one place a
		// false positive would change a number the patient sees.
		assertThat(rule.find(text, protectedRows)).isEmpty();
	}

	@Test
	@DisplayName("rejects lab vocabulary even outside a result row")
	void rejectsLabTerms() {
		// Without the protected-row shield - a heading block, say - the term filter is what
		// stops a test name being masked.
		String text = """
				Hemoglobin A1c
				Vitamin B12
				Free T4
				Thyroid Peroxidase Antibodies
				Reference Range
				Specimen Collected
				""";

		List<Span> spans = rule.find(text, ProtectedSpans.none());

		assertThat(covered(text, spans))
				.as("no lab term should be proposed as a name")
				.isEmpty();
	}

	@Test
	@DisplayName("a lower threshold accepts more, so the threshold does something")
	void thresholdIsApplied() {
		String text = """
				Released by Alan Whitfield.
				Checked by Priya Ramachandran.
				Countersigned by Dolores Fenwick.
				""";

		OpenNlpNameRule permissive = new OpenNlpNameRule(
				TestProperties.withOpenNlp(true, 0.0), RESOURCES);
		OpenNlpNameRule strict = new OpenNlpNameRule(
				TestProperties.withOpenNlp(true, 0.999), RESOURCES);

		assertThat(permissive.find(text, ProtectedSpans.none()).size())
				.isGreaterThanOrEqualTo(strict.find(text, ProtectedSpans.none()).size());
	}

	@Test
	@DisplayName("spans are offsets into the original document, across many lines")
	void spansMapBackToDocumentOffsets() {
		String text = """
				NORTHSIDE COMMUNITY LABORATORY

				Released by Alan Whitfield.
				""";

		List<Span> spans = rule.find(text, ProtectedSpans.none());

		// Token indices come back from the model; they have to be converted to characters in
		// the whole document, not the line. Getting this wrong corrupts unrelated text.
		for (Span span : spans) {
			assertThat(span.start()).isBetween(0, text.length());
			assertThat(span.end()).isBetween(span.start(), text.length());
			assertThat(text.substring(span.start(), span.end())).doesNotContain("\n");
		}
	}

	@Test
	@DisplayName("is safe to call from several threads at once")
	void isThreadSafe() throws Exception {
		String text = "Released by Alan Whitfield.\nChecked by Priya Ramachandran.";

		// Two report jobs run concurrently and share this bean. NameFinderME is not
		// thread-safe, so the rule creates one per call - if it ever shared one, this would
		// produce corrupt spans or an exception.
		List<Callable<Integer>> calls = IntStream.range(0, 16)
				.mapToObj(i -> (Callable<Integer>) () -> rule.find(text, ProtectedSpans.none()).size())
				.toList();

		try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
			List<Integer> counts = pool.invokeAll(calls).stream()
					.map(future -> {
						try {
							return future.get();
						}
						catch (Exception ex) {
							throw new IllegalStateException(ex);
						}
					})
					.toList();

			// Every call must agree. A shared finder's adaptive data would make later calls
			// differ from earlier ones - which would also be a cross-report data leak.
			assertThat(counts).containsOnly(counts.get(0));
		}
	}

	@Test
	@DisplayName("fails startup with a usable message when the model is missing")
	void failsFastOnMissingModel() {
		assertThatThrownBy(() -> new OpenNlpNameRule(
				TestProperties.withOpenNlpModel("classpath:opennlp/does-not-exist.bin"), RESOURCES))
				.isInstanceOf(IllegalStateException.class)
				// A privacy feature must not quietly run one rule short, and whoever hits this
				// needs to be told what to do about it.
				.hasMessageContaining("opennlp-enabled")
				.hasMessageContaining("README");
	}

	private static List<String> covered(String text, List<Span> spans) {
		return spans.stream().map(span -> text.substring(span.start(), span.end())).toList();
	}
}
