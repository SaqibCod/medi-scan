package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.saq.mediscan.chat.ContentChunkRepository;
import dev.saq.mediscan.chat.ContentIndexer;
import dev.saq.mediscan.chat.EmbeddingGateway;
import dev.saq.mediscan.chat.ScoredChunk;
import dev.saq.mediscan.support.PostgresTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tunes {@code rag.min-similarity} using retrieval only ({@code LLD} 12, "Tuning").
 *
 * <p><strong>Embedding calls, no chat calls.</strong> That is the whole point of running this
 * before the full evaluation: retrieval quality bounds answer quality — a chunk that was never
 * retrieved cannot help an answer — and measuring it costs one embedding per question instead
 * of an embedding plus a generation. On a free tier that difference decides whether the
 * threshold can be swept at all.
 *
 * <p><strong>Each question is embedded once, not once per threshold.</strong> The vector does
 * not depend on the threshold; only the filter does. Sweeping three values therefore costs the
 * same as measuring one, which is worth stating because the obvious loop ordering costs three
 * times as much for identical numbers.
 *
 * <p>Tagged so it never runs in {@code ./mvnw verify}: it spends real quota.
 * Run with {@code ./mvnw test -Pchat-tuning}.
 */
@org.springframework.test.context.ActiveProfiles("eval")
@Tag("chat-tuning")
class ChatRetrievalTuningRun extends PostgresTestBase {

	private static final double[] THRESHOLDS = { 0.5, 0.6, 0.7 };

	private static final int TOP_K = 4;

	private static final Path QUESTIONS = Paths.get("..", "eval", "chat", "questions.json");

	private static final Path RESULTS = Paths.get("..", "eval", "results");

	@Autowired
	private EmbeddingGateway embeddings;

	@Autowired
	private ContentChunkRepository repository;

	@Autowired
	private ContentIndexer indexer;

	@Test
	@DisplayName("sweep rag.min-similarity over the chat question set")
	void sweep() throws Exception {
		if (repository.firstChunkOf("ldl-cholesterol").isEmpty()) {
			indexer.index();
		}

		List<Question> questions = loadQuestions();
		assertThat(questions).as("the chat question set must load").isNotEmpty();

		// One embedding per question, reused for every threshold.
		Map<Question, float[]> vectors = new LinkedHashMap<>();
		for (Question question : questions) {
			vectors.put(question, embeddings.embedQuery(question.text()));
		}

		StringBuilder report = new StringBuilder();
		report.append("# Chat retrieval tuning\n\n")
				.append("Run: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
				.append("\nEmbedding model: ").append(embeddings.model())
				.append(" (").append(embeddings.dimensions()).append(" dimensions)")
				.append("\nQuestions: ").append(questions.size())
				.append("  ·  embedding calls: ").append(questions.size())
				.append("  ·  chat calls: 0\n\n")
				.append("| min-similarity | questions with a hit | mean chunks | empty results | off-topic chunks |\n")
				.append("|---|---:|---:|---:|---:|\n");

		for (double threshold : THRESHOLDS) {
			Totals totals = measure(questions, vectors, threshold);
			report.append(String.format("| %.1f | %d / %d (%.0f%%) | %.2f | %d | %.0f%% |%n",
					threshold, totals.hits, totals.expected, totals.hitRate() * 100,
					totals.meanChunks(), totals.empty, totals.offTopicRate() * 100));
		}

		report.append("\n**hit** = the page the question is about appeared in the retrieved set. ")
				.append("Only counted over questions that name an expected page.\n\n")
				.append("**off-topic** = a retrieved chunk from a page unrelated to the question. ")
				.append("These are what a reader sees under \"Sources\", so they are a visible ")
				.append("cost even when the answer itself is right.\n\n")
				.append("**empty** = no chunk cleared the threshold. Not a failure: the answer ")
				.append("then rests on the report alone, which beats citing a page about a ")
				.append("different test.\n");

		Files.createDirectories(RESULTS);
		Path out = RESULTS.resolve("chat-retrieval-tuning-"
				+ LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
		Files.writeString(out, report.toString(), StandardCharsets.UTF_8);

		System.out.println(report);
		System.out.println("written to " + out.toAbsolutePath().normalize());
	}

	private Totals measure(List<Question> questions, Map<Question, float[]> vectors,
			double threshold) {

		Totals totals = new Totals();

		for (Question question : questions) {
			List<ScoredChunk> found = repository.search(vectors.get(question), TOP_K, threshold);

			totals.questions++;
			totals.chunks += found.size();
			if (found.isEmpty()) {
				totals.empty++;
			}

			if (question.expectedSource() != null) {
				totals.expected++;
				// Counted only over questions that name an expected page: a question with no
				// expectation has no off-topic chunks by definition, and including its
				// retrievals in the denominator would quietly dilute the rate.
				totals.chunksForExpected += found.size();

				boolean hit = found.stream()
						.anyMatch(chunk -> chunk.chunk().slug().equals(question.expectedSource()));
				if (hit) {
					totals.hits++;
				}
				for (ScoredChunk chunk : found) {
					if (!chunk.chunk().slug().equals(question.expectedSource())) {
						totals.offTopic++;
					}
				}
			}
		}
		return totals;
	}

	private static List<Question> loadQuestions() throws IOException {
		JsonNode root = JsonMapper.builder().build()
				.readTree(Files.readString(QUESTIONS, StandardCharsets.UTF_8));

		List<Question> questions = new ArrayList<>();
		for (JsonNode node : root.get("questions")) {
			JsonNode expects = node.get("expectsSource");
			questions.add(new Question(
					node.get("id").asString(),
					node.get("group").asString(),
					node.get("text").asString(),
					expects == null ? null : expects.asString()));
		}
		return questions;
	}

	private record Question(String id, String group, String text, String expectedSource) {
	}

	private static final class Totals {

		int questions;
		int expected;
		int hits;
		int chunks;
		int chunksForExpected;
		int empty;
		int offTopic;

		double hitRate() {
			return expected == 0 ? 0 : (double) hits / expected;
		}

		double meanChunks() {
			return questions == 0 ? 0 : (double) chunks / questions;
		}

		/** Off-topic chunks as a share of everything retrieved for questions with an expectation. */
		double offTopicRate() {
			return chunksForExpected == 0 ? 0 : (double) offTopic / chunksForExpected;
		}
	}
}
