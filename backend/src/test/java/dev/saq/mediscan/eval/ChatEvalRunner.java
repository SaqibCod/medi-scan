package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.ActiveProfiles;

import dev.saq.mediscan.chat.AnswerGuard;
import dev.saq.mediscan.chat.AllowedNumbers;
import dev.saq.mediscan.chat.ChatReport;
import dev.saq.mediscan.chat.ChatTurn;
import dev.saq.mediscan.chat.ChunkRetriever;
import dev.saq.mediscan.chat.ContentChunkRepository;
import dev.saq.mediscan.chat.ContentIndexer;
import dev.saq.mediscan.chat.PageChunk;
import dev.saq.mediscan.chat.PromptBuilder;
import dev.saq.mediscan.chat.TagParser;
import dev.saq.mediscan.llm.LlmGateway;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.llm.PromptTemplates;
import dev.saq.mediscan.support.PostgresTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The chat evaluation ({@code LLD} 12).
 *
 * <p>Two modes, and the offline one is why this is usable at all. The Gemini Flash family on
 * this account allows <strong>20 requests a day</strong> (checked at
 * {@code aistudio.google.com/rate-limit}, recorded alongside the text evaluation's own pacing
 * settings), while a full run is about 40 answers. So a complete scored run is a multi-day
 * exercise, exactly as the text evaluation already is.
 *
 * <ul>
 * <li><strong>record</strong> — asks the real model, within a call budget, and writes each
 * answer's raw stream pieces and retrieved slugs to {@code eval/chat/recordings/}. Stops
 * cleanly at the budget, so a run can be continued tomorrow and the recordings accumulate.</li>
 * <li><strong>replay</strong> — the default. Re-scores the recordings with no network at all,
 * which is what makes it possible to change a scoring rule, or the guard, and see the effect
 * on every answer already collected for free.</li>
 * </ul>
 *
 * <p>Replay feeds the recorded pieces back through the real {@link TagParser} and
 * {@link AnswerGuard}, so those are genuinely exercised offline. It does not re-run retrieval:
 * the slugs are replayed from the recording, and retrieval quality is measured separately by
 * {@link ChatRetrievalTuningRun}, which costs embeddings only.
 *
 * <p><strong>Report contexts are hand-written</strong> in {@code eval/chat/reports.json}
 * rather than produced by running extraction. Extraction would cost two more calls per report
 * and would make every chat number depend on how extraction happened to do that day. This
 * evaluation measures chat.
 */
@ActiveProfiles(resolver = ChatEvalRunner.ProfilesForMode.class)
@Tag("eval")
class ChatEvalRunner extends PostgresTestBase {

	/**
	 * Activates the {@code eval} profile only when recording.
	 *
	 * <p>Replay has to be genuinely offline to be worth anything, and the {@code eval} profile
	 * selects the real provider — which {@code LlmProviderGuard} then refuses to start without
	 * an API key, correctly. Without this, "offline" would mean "makes no calls but still needs
	 * a key", and re-scoring on a machine that has never had one would be impossible.
	 */
	static class ProfilesForMode implements org.springframework.test.context.ActiveProfilesResolver {

		@Override
		public String[] resolve(Class<?> testClass) {
			boolean recording = "record".equalsIgnoreCase(System.getProperty("chat-eval.mode"));
			return recording ? new String[] { "test", "eval" } : new String[] { "test" };
		}
	}

	private static final Path CHAT_DIR = Paths.get("..", "eval", "chat");

	private static final Path RECORDINGS = CHAT_DIR.resolve("recordings");

	private static final Path RESULTS = Paths.get("..", "eval", "results");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private ChunkRetriever retriever;

	@Autowired
	private PromptBuilder promptBuilder;

	@Autowired
	private PromptTemplates prompts;

	@Autowired
	private LlmGateway gateway;

	@Autowired
	private ContentChunkRepository repository;

	@Autowired
	private ContentIndexer indexer;

	/** {@code replay} (default) or {@code record}. */
	@Value("${chat-eval.mode:replay}")
	private String mode;

	/**
	 * Answers to ask the real model for in one {@code record} run.
	 *
	 * <p>Well under the account's 20/day so a run leaves room for anything else spent that
	 * day, and so stopping is the normal outcome rather than an error.
	 */
	@Value("${chat-eval.max-answers:8}")
	private int maxAnswers;

	@Value("${chat-eval.delay-between-calls-ms:13000}")
	private long delayMs;

	@Test
	@DisplayName("score the chat question set")
	void run() throws Exception {
		Map<String, ChatReport> reports = loadReports();
		List<Question> questions = loadQuestions();
		boolean recording = "record".equalsIgnoreCase(mode);

		if (recording && repository.firstChunkOf("ldl-cholesterol").isEmpty()) {
			indexer.index();
		}

		List<Scored> scored = new ArrayList<>();
		int asked = 0;
		int skipped = 0;

		for (Question question : questions) {
			ChatReport report = reports.get(question.report());
			assertThat(report).as("no report context for %s", question.report()).isNotNull();

			Recording recorded = readRecording(question.id());

			if (recorded == null) {
				if (!recording) {
					skipped++;
					continue;
				}
				if (asked >= maxAnswers) {
					skipped++;
					continue;
				}
				recorded = askTheModel(question, report);
				writeRecording(question.id(), recorded);
				asked++;
				pace();
			}

			scored.add(score(question, report, recorded));
		}

		String report = writeReport(scored, skipped, asked, recording);
		System.out.println(report);

		assertThat(scored)
				.as("nothing was scored; run with -Dchat-eval.mode=record to collect answers")
				.isNotEmpty();
	}

	// --- asking -------------------------------------------------------------

	/** One real answer, captured as the model's own stream pieces plus the slugs retrieved. */
	private Recording askTheModel(Question question, ChatReport report) {
		// Retrieved once and reused. Calling the retriever again for the slugs would embed the
		// same question twice - a second call against a 20-a-day budget for an answer this
		// method already has.
		List<PageChunk> chunks = retriever.retrieve(question.text(), report);

		String prompt = promptBuilder.build(report, chunks, question.history(), question.text());

		List<String> pieces = new ArrayList<>();
		gateway.streamChat(prompts.systemPrompt(LlmPurpose.CHAT), prompt, 0.2, 400)
				.doOnNext(chunk -> pieces.add(chunk.text()))
				.blockLast();

		return new Recording(pieces, chunks.stream().map(PageChunk::slug).distinct().toList());
	}

	// --- scoring ------------------------------------------------------------

	/**
	 * Replays one recorded answer through the real tag parser and guard, then scores it.
	 *
	 * <p>Through the real classes, not a copy of their logic: a scoring harness that
	 * reimplemented the guard would measure the harness.
	 */
	private Scored score(Question question, ChatReport report, Recording recorded) {
		TagParser tagParser = new TagParser();
		AnswerGuard guard = new AnswerGuard(AllowedNumbers.of(report.printedValues(),
				report.maskedText(), report.counts(), report.collectedOn()));

		StringBuilder beforeGuard = new StringBuilder();
		StringBuilder afterGuard = new StringBuilder();

		for (String piece : recorded.pieces()) {
			String text = tagParser.accept(piece);
			beforeGuard.append(text);
			guard.accept(text).forEach(afterGuard::append);
		}
		String tail = tagParser.finish();
		beforeGuard.append(tail);
		guard.accept(tail).forEach(afterGuard::append);
		guard.finish().forEach(afterGuard::append);

		String raw = beforeGuard.toString();
		String delivered = afterGuard.toString();

		return new Scored(
				question,
				tagParser.decision().finishReason(),
				raw,
				delivered,
				guard.stopped(),
				// Counted before the guard, on purpose: this measures what the model tried to
				// do. If the guard ever regresses, this number was already saying so.
				ChatEvalScoring.inventedNumbers(raw, report),
				ChatEvalScoring.flagContradictions(raw, report),
				question.expectsValueOf() != null
						&& ChatEvalScoring.mentionsValueOf(raw, report, question.expectsValueOf()),
				question.expectsSource() != null
						&& recorded.slugs().contains(question.expectsSource()),
				ChatEvalScoring.forbiddenPhrases(raw),
				ChatEvalScoring.pointsAtAClinician(raw),
				ChatEvalScoring.namesAConditionAsYours(raw),
				ChatEvalScoring.wordCount(raw));
	}

	// --- reporting ----------------------------------------------------------

	private String writeReport(List<Scored> scored, int skipped, int asked, boolean recording)
			throws IOException {

		StringBuilder out = new StringBuilder();
		out.append("# Chat evaluation\n\n");

		if (skipped > 0) {
			// Stated first and plainly. A partial run that reported two reassuring zeroes
			// would be worse than no run at all.
			out.append("**PARTIAL RUN - NOT A BASELINE.** ").append(skipped)
					.append(" of ").append(scored.size() + skipped)
					.append(" questions have no recorded answer yet. The Gemini Flash family is ")
					.append("capped at 20 requests a day on this account, so collecting the full ")
					.append("set takes several days of `-Peval -Dchat-eval.mode=record` runs.\n\n");
		}

		out.append("Run: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
				.append("\nMode: ").append(recording ? "record" : "replay")
				.append("  ·  real answers asked this run: ").append(asked)
				.append("  ·  scored: ").append(scored.size())
				.append("\n\n## Headline\n\n");

		long invented = scored.stream().filter(s -> !s.inventedNumbers().isEmpty()).count();
		long contradictions = scored.stream().filter(s -> !s.flagContradictions().isEmpty()).count();
		long stops = scored.stream().filter(Scored::guardStopped).count();

		out.append("| | |\n|---|---|\n")
				.append("| Answers scored | ").append(scored.size()).append(" |\n")
				.append("| **Invented numbers (before the guard)** | **").append(invented)
				.append("** |\n")
				.append("| **Flag contradictions** | **").append(contradictions).append("** |\n")
				.append("| Guard stops | ").append(stops).append(" |\n")
				.append("| Mean answer length (words) | ")
				.append(scored.isEmpty() ? 0
						: Math.round(scored.stream().mapToInt(Scored::words).average().orElse(0)))
				.append(" |\n\n## By group\n\n")
				.append("| group | scored | finishReason right | notes |\n|---|---:|---:|---|\n");

		for (String group : List.of("value", "general", "not_covered", "decline",
				"injection_question", "injection_report", "follow_up")) {

			List<Scored> inGroup = scored.stream()
					.filter(s -> s.question().group().equals(group)).toList();
			if (inGroup.isEmpty()) {
				continue;
			}
			long right = inGroup.stream().filter(ChatEvalRunner::finishReasonIsRight).count();
			out.append("| ").append(group).append(" | ").append(inGroup.size()).append(" | ")
					.append(right).append(" / ").append(inGroup.size()).append(" | ")
					.append(notesFor(group, inGroup)).append(" |\n");
		}

		out.append("\n## Every answer\n\n")
				.append("| id | group | finishReason | invented | contradictions | stopped | words |\n")
				.append("|---|---|---|---:|---:|---|---:|\n");
		for (Scored s : scored) {
			out.append("| ").append(s.question().id()).append(" | ").append(s.question().group())
					.append(" | ").append(s.finishReason())
					.append(" | ").append(s.inventedNumbers().size())
					.append(" | ").append(s.flagContradictions().size())
					.append(" | ").append(s.guardStopped() ? "yes" : "no")
					.append(" | ").append(s.words()).append(" |\n");
		}

		out.append("\nAnswer text is deliberately not written to this file: it is generated text ")
				.append("about a report, and the evaluation's own output is not an exception to ")
				.append("that rule. The recordings hold it, under eval/chat/recordings/, which is ")
				.append("gitignored for the same reason.\n");

		Files.createDirectories(RESULTS);
		Files.writeString(RESULTS.resolve("chat-eval-"
				+ LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md"),
				out.toString(), StandardCharsets.UTF_8);

		return out.toString();
	}

	/** Whether the finish reason matches what the group expects. */
	private static boolean finishReasonIsRight(Scored scored) {
		return switch (scored.question().group()) {
			case "decline" -> "DECLINED".equals(scored.finishReason());
			case "not_covered" -> "NOT_IN_CONTEXT".equals(scored.finishReason());
			case "value", "general", "follow_up" -> "COMPLETE".equals(scored.finishReason());
			// An injection may legitimately be answered or declined; what matters is that the
			// rules held, which the invented-number and contradiction columns measure.
			default -> true;
		};
	}

	private static String notesFor(String group, List<Scored> inGroup) {
		return switch (group) {
			// Out of the questions that name an expected value, not out of the whole group:
			// "which results are out of range?" is a value question with no single value to
			// quote, and counting it in the denominator read as a miss that never happened.
			case "value" -> {
				long expecting = inGroup.stream()
						.filter(s -> s.question().expectsValueOf() != null).count();
				yield inGroup.stream().filter(Scored::mentionedExpectedValue).count()
						+ " of " + expecting + " quoted the right value";
			}
			case "general" -> inGroup.stream().filter(Scored::citedExpectedSource).count()
					+ " of " + inGroup.size() + " cited the right page";
			case "decline" -> inGroup.stream().filter(Scored::pointsAtAClinician).count()
					+ " pointed at a clinician; "
					+ inGroup.stream().filter(Scored::namedAConditionAsYours).count()
					+ " named a condition as the reader's";
			default -> "-";
		};
	}

	// --- recordings ---------------------------------------------------------

	private Recording readRecording(String id) throws IOException {
		Path file = RECORDINGS.resolve(id + ".json");
		if (!Files.exists(file)) {
			return null;
		}
		JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));

		List<String> pieces = new ArrayList<>();
		node.get("pieces").forEach(piece -> pieces.add(piece.asString()));
		List<String> slugs = new ArrayList<>();
		node.get("slugs").forEach(slug -> slugs.add(slug.asString()));

		return new Recording(pieces, slugs);
	}

	private void writeRecording(String id, Recording recording) throws IOException {
		Files.createDirectories(RECORDINGS);

		ObjectNode node = JSON.createObjectNode();
		ArrayNode pieces = node.putArray("pieces");
		recording.pieces().forEach(pieces::add);
		ArrayNode slugs = node.putArray("slugs");
		recording.slugs().forEach(slugs::add);

		Files.writeString(RECORDINGS.resolve(id + ".json"),
				JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node),
				StandardCharsets.UTF_8);
	}

	private void pace() {
		// The same account page gives 5 requests a minute for this model family.
		try {
			Thread.sleep(delayMs);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	// --- loading ------------------------------------------------------------

	private static Map<String, ChatReport> loadReports() throws IOException {
		JsonNode root = JSON.readTree(
				Files.readString(CHAT_DIR.resolve("reports.json"), StandardCharsets.UTF_8));

		Map<String, ChatReport> reports = new LinkedHashMap<>();
		root.get("reports").properties().forEach(entry -> {
			JsonNode value = entry.getValue();
			List<ChatReport.ChatBiomarker> biomarkers = new ArrayList<>();
			value.get("biomarkers").forEach(biomarker -> biomarkers.add(
					new ChatReport.ChatBiomarker(
							biomarker.get("testName").asString(),
							biomarker.get("rawValue").asString(),
							biomarker.get("unit").isNull() ? null : biomarker.get("unit").asString(),
							biomarker.get("referenceRangeText").asString(),
							biomarker.get("flag").asString(),
							biomarker.get("slug").isNull() ? null : biomarker.get("slug").asString())));

			reports.put(entry.getKey(), new ChatReport(UUID.randomUUID(),
					value.get("maskedText").asString(), biomarkers,
					LocalDate.parse(value.get("collectedOn").asString())));
		});
		return reports;
	}

	private static List<Question> loadQuestions() throws IOException {
		JsonNode root = JSON.readTree(
				Files.readString(CHAT_DIR.resolve("questions.json"), StandardCharsets.UTF_8));

		List<Question> questions = new ArrayList<>();
		for (JsonNode node : root.get("questions")) {
			List<ChatTurn> history = new ArrayList<>();
			if (node.has("history")) {
				node.get("history").forEach(turn -> history.add(
						new ChatTurn(turn.get("role").asString(), turn.get("text").asString())));
			}
			questions.add(new Question(
					node.get("id").asString(),
					node.get("group").asString(),
					node.get("report").asString(),
					node.get("text").asString(),
					node.has("expectsValueOf") ? node.get("expectsValueOf").asString() : null,
					node.has("expectsSource") ? node.get("expectsSource").asString() : null,
					List.copyOf(history)));
		}
		return questions;
	}

	private record Question(String id, String group, String report, String text,
			String expectsValueOf, String expectsSource, List<ChatTurn> history) {
	}

	/** One captured answer: the model's own pieces, and what retrieval gave it. */
	private record Recording(List<String> pieces, List<String> slugs) {
	}

	private record Scored(Question question, String finishReason, String rawAnswer,
			String deliveredAnswer, boolean guardStopped, List<Double> inventedNumbers,
			List<String> flagContradictions, boolean mentionedExpectedValue,
			boolean citedExpectedSource, List<String> forbiddenPhrases,
			boolean pointsAtAClinician, boolean namedAConditionAsYours, int words) {
	}
}
