package dev.saq.mediscan.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import dev.saq.mediscan.llm.DailyCapGuard;
import dev.saq.mediscan.llm.FakeLlmProvider;
import dev.saq.mediscan.llm.LlmException;
import dev.saq.mediscan.report.Flag;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * The chat endpoint end to end ({@code LLD} 13, contract 4.4).
 *
 * <p>The contract is specific about where the boundary is, and these tests hold it: everything
 * that can refuse a request produces ordinary JSON Problem Details, and everything that fails
 * after the stream opens produces an SSE {@code error} event with no {@code done} after it. A
 * client switches on {@code code} for the first and on the event name for the second, so
 * getting this wrong breaks error handling in a way no unit test would show.
 */
class ChatEndpointTest extends PostgresTestBase {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private FakeLlmProvider provider;

	@Autowired
	private ChatCache cache;

	@Autowired
	private DailyCapGuard capGuard;

	@Autowired
	private JdbcClient jdbc;

	private String token;
	private UUID reportId;

	@BeforeEach
	void setUp() {
		fixtures.clear();
		provider.reset();
		jdbc.sql("delete from llm_usage").update();

		token = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(token);
		reportId = lipidReport(sessionId);
	}

	// --- the happy path -----------------------------------------------------

	@Nested
	@DisplayName("a normal answer")
	class HappyPath {

		@Test
		@DisplayName("events arrive as meta, tokens, sources, done")
		void eventOrder() throws Exception {
			provider.streamPieces("[[ANSWER]] Your LDL is 162 mg/dL. ",
					"That is above the printed range of <100. ");

			String body = chat("Why is my LDL flagged?");

			assertThat(eventNames(body)).startsWith("meta").endsWith("done");
			assertThat(eventNames(body)).containsSubsequence("meta", "token", "sources", "done");
			assertThat(body).contains("\"finishReason\":\"COMPLETE\"");
			// The tag is the server's business, never the reader's.
			assertThat(body).doesNotContain("[[ANSWER]]");
		}

		@Test
		@DisplayName("a declined question reports DECLINED")
		void declined() throws Exception {
			provider.streamPieces("[[DECLINE]] A doctor or nurse can answer that. ");

			assertThat(chat("Should I take statins?"))
					.contains("\"finishReason\":\"DECLINED\"")
					.doesNotContain("[[DECLINE]]");
		}

		@Test
		@DisplayName("an uncovered question reports NOT_IN_CONTEXT")
		void notInContext() throws Exception {
			provider.streamPieces("[[NOT_IN_CONTEXT]] Your report does not show that. ");

			assertThat(chat("What is my blood type?"))
					.contains("\"finishReason\":\"NOT_IN_CONTEXT\"");
		}

		@Test
		@DisplayName("meta says the answer was not cached")
		void metaReportsCacheState() throws Exception {
			provider.streamPieces("[[ANSWER]] Your LDL is 162 mg/dL. ");

			assertThat(chat("Why is my LDL flagged?")).contains("\"cached\":false");
		}
	}

	// --- errors before the stream -------------------------------------------

	@Nested
	@DisplayName("refusals before the stream opens")
	class BeforeTheStream {

		@Test
		@DisplayName("an empty question is 400 VALIDATION_ERROR")
		void emptyQuestion() throws Exception {
			mockMvc.perform(chatRequest(reportId, "{\"question\":\"  \"}", token))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

		@Test
		@DisplayName("an over-long question is 400 VALIDATION_ERROR")
		void overLongQuestion() throws Exception {
			String body = "{\"question\":\"" + "x".repeat(501) + "\"}";

			mockMvc.perform(chatRequest(reportId, body, token))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

		@Test
		@DisplayName("more than six history turns is 400 VALIDATION_ERROR")
		void tooMuchHistory() throws Exception {
			StringBuilder turns = new StringBuilder();
			for (int i = 0; i < 7; i++) {
				turns.append(i > 0 ? "," : "").append("{\"role\":\"user\",\"content\":\"q\"}");
			}

			mockMvc.perform(chatRequest(reportId,
					"{\"question\":\"Why?\",\"history\":[" + turns + "]}", token))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

		@Test
		@DisplayName("an unknown role is 400 VALIDATION_ERROR")
		void badRole() throws Exception {
			mockMvc.perform(chatRequest(reportId,
					"{\"question\":\"Why?\",\"history\":[{\"role\":\"system\",\"content\":\"x\"}]}",
					token))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

		@Test
		@DisplayName("no session token is 401 SESSION_INVALID")
		void missingCredential() throws Exception {
			mockMvc.perform(post("/api/reports/" + reportId + "/chat")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"question\":\"Why?\"}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("SESSION_INVALID"));
		}

		@Test
		@DisplayName("an unknown report is 404 REPORT_NOT_FOUND")
		void unknownReport() throws Exception {
			mockMvc.perform(chatRequest(UUID.randomUUID(), "{\"question\":\"Why?\"}", token))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
		}

		@Test
		@DisplayName("a report that is not DONE is 409 REPORT_NOT_READY")
		void notReady() throws Exception {
			UUID pending = fixtures.pendingReport(fixtures.sessionIdFor(token));

			mockMvc.perform(chatRequest(pending, "{\"question\":\"Why?\"}", token))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("REPORT_NOT_READY"));
		}

		@Test
		@DisplayName("the daily cap being reached is 429 CAPACITY")
		void capacityReached() throws Exception {
			while (capGuard.tryAcquire()) {
				// Spend the whole budget.
			}

			mockMvc.perform(chatRequest(reportId, "{\"question\":\"Why?\"}", token))
					.andExpect(status().isTooManyRequests())
					.andExpect(jsonPath("$.code").value("CAPACITY"));
		}
	}

	// --- owner isolation ----------------------------------------------------

	@Nested
	@DisplayName("owner isolation")
	class Ownership {

		@Test
		@DisplayName("a guest cannot chat with another guest's report")
		void otherGuestsReport() throws Exception {
			String otherToken = fixtures.sessionToken();

			// 404, not 403: the API never confirms that an id exists.
			mockMvc.perform(chatRequest(reportId, "{\"question\":\"Why?\"}", otherToken))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
		}

		@Test
		@DisplayName("a guest cannot chat with a user's report")
		void usersReport() throws Exception {
			UUID userId = fixtures.user("chat-owner-sub", dev.saq.mediscan.user.UserRole.USER);
			UUID userReport = fixtures.doneUserReport(userId, LocalDate.of(2026, 9, 28));
			fixtures.reportText(userReport, "Total Cholesterol 238 mg/dL");

			mockMvc.perform(chatRequest(userReport, "{\"question\":\"Why?\"}", token))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
		}

		@Test
		@DisplayName("an expired report is 404")
		void expiredReport() throws Exception {
			UUID expired = fixtures.expiredReport(fixtures.sessionIdFor(token));

			mockMvc.perform(chatRequest(expired, "{\"question\":\"Why?\"}", token))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
		}
	}

	// --- failures after the stream opens ------------------------------------

	@Nested
	@DisplayName("failures after the stream opens")
	class AfterTheStream {

		@Test
		@DisplayName("a mid-stream provider failure sends error and no done")
		void midStreamFailure() throws Exception {
			provider.queueStream(new FakeLlmProvider.StreamScript.FailAfter(
					List.of("[[ANSWER]] Your LDL is 162 mg/dL. "),
					new LlmException.TransientLlmException("provider died", null)));

			String body = chat("Why is my LDL flagged?");

			assertThat(body).contains("event:error").contains("LLM_UNAVAILABLE");
			// The contract: error replaces sources and done.
			assertThat(eventNames(body)).doesNotContain("done");
		}

		@Test
		@DisplayName("the partial answer before a failure is kept")
		void partialAnswerSurvivesAFailure() throws Exception {
			provider.queueStream(new FakeLlmProvider.StreamScript.FailAfter(
					List.of("[[ANSWER]] Your LDL is 162 mg/dL. "),
					new LlmException.TransientLlmException("gone", null)));

			assertThat(chat("Why?")).contains("162");
		}
	}

	// --- the number guard ---------------------------------------------------

	@Nested
	@DisplayName("the number guard")
	class Guard {

		@Test
		@DisplayName("an invented number stops the answer and still ends properly")
		void inventedNumberStopsTheAnswer() throws Exception {
			provider.streamPieces("[[ANSWER]] Your LDL is 162 mg/dL. ",
					"A normal LDL is under 130 mg/dL. ");

			String body = chat("Why is my LDL flagged?");

			assertThat(body).contains("162");
			// The invented cut-off never reaches the reader.
			assertThat(body).doesNotContain("130");
			assertThat(body).contains("stopped this answer here");
			assertThat(body).contains("\"finishReason\":\"COMPLETE\"");
			assertThat(eventNames(body)).containsSubsequence("sources", "done");
		}

		@Test
		@DisplayName("a stopped answer is counted and not cached")
		void stoppedAnswersAreNotCached() throws Exception {
			provider.streamPieces("[[ANSWER]] Normal is under 130. ");
			chat("Is 130 normal?");

			assertThat(chatNumberStops()).isPositive();
			// Caching a truncated answer would serve the same truncation to the next reader
			// without the model ever being asked again.
			provider.streamPieces("[[ANSWER]] Your LDL is 162 mg/dL. ");
			assertThat(chat("Is 130 normal?")).contains("\"cached\":false");
		}
	}

	// --- the cache ----------------------------------------------------------

	@Nested
	@DisplayName("the cache")
	class Cache {

		@Test
		@DisplayName("a repeated first question is served from the cache")
		void repeatedQuestionIsCached() throws Exception {
			provider.streamPieces("[[ANSWER]] Your LDL is 162 mg/dL. ");
			chat("Why is my LDL flagged?");

			int capBefore = capGuard.usedToday();
			String second = chat("Why is my LDL flagged?");

			assertThat(second).contains("\"cached\":true").contains("162");
			assertThat(capGuard.usedToday())
					.as("a cache hit must cost no model or embedding call")
					.isEqualTo(capBefore);
		}

		@Test
		@DisplayName("the same question with history is not cached")
		void followUpsAreNotCached() throws Exception {
			provider.streamPieces("[[ANSWER]] Your LDL is 162 mg/dL. ");
			chat("Why is my LDL flagged?");

			provider.streamPieces("[[ANSWER]] Your HDL is 52 mg/dL. ");
			String body = chatWithHistory("Why is my LDL flagged?",
					"{\"role\":\"user\",\"content\":\"earlier\"}");

			// A follow-up depends on the exchange it follows, so serving the cached answer
			// would answer a different conversation.
			assertThat(body).contains("\"cached\":false");
		}

		@Test
		@DisplayName("deleting the report drops its cached answers")
		void deletionEvictsTheCache() throws Exception {
			provider.streamPieces("[[ANSWER]] Your LDL is 162 mg/dL. ");
			chat("Why is my LDL flagged?");

			mockMvc.perform(delete("/api/reports/" + reportId)
					.header("X-Session-Token", token))
					.andExpect(status().isNoContent());

			// "Delete" has to mean it: sentences quoting a deleted report must not be
			// replayable, even though the owner check would already 404 the request.
			assertThat(cache.find(reportId, "why is my ldl flagged?", List.of())).isEmpty();
		}
	}

	// --- privacy ------------------------------------------------------------

	@Nested
	@DisplayName("privacy")
	class Privacy {

		@Test
		@DisplayName("no chat question or answer is written to any table")
		void nothingIsStored() throws Exception {
			String canary = "ZZCANARY-Question-7391";
			provider.streamPieces("[[ANSWER]] Your LDL is 162 mg/dL. ");

			chat("What about " + canary + "?");

			// Every text column in the database, scanned. CLAUDE.md rule 9: chat messages are
			// never stored, and the only way to assert that is to look everywhere.
			assertThat(textColumnsContaining(canary))
					.as("the question must not appear in any column")
					.isEmpty();
		}

		@Test
		@DisplayName("the answer text is not stored either")
		void answerIsNotStored() throws Exception {
			provider.streamPieces("[[ANSWER]] ZZCANARY-Answer-4412 is distinctive. ");
			chat("Why is my LDL flagged?");

			assertThat(textColumnsContaining("ZZCANARY-Answer-4412")).isEmpty();
		}
	}

	// --- helpers ------------------------------------------------------------

	private String chat(String question) throws Exception {
		return chatWithHistory(question, null);
	}

	private String chatWithHistory(String question, String historyJson) throws Exception {
		String body = historyJson == null
				? "{\"question\":" + ChatStreamWriter.quote(question) + "}"
				: "{\"question\":" + ChatStreamWriter.quote(question)
						+ ",\"history\":[" + historyJson + "]}";

		MvcResult started = mockMvc.perform(chatRequest(reportId, body, token))
				.andExpect(status().isOk())
				.andReturn();

		// The handler returns immediately and answers on another thread; this waits for it.
		started.getAsyncResult(10_000);
		return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.asyncDispatch(started))
				.andReturn().getResponse().getContentAsString();
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
			chatRequest(UUID id, String body, String sessionToken) {

		return post("/api/reports/" + id + "/chat")
				.header("X-Session-Token", sessionToken)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	/** The SSE event names in order, which is what the contract specifies. */
	private static List<String> eventNames(String body) {
		return body.lines()
				.filter(line -> line.startsWith("event:"))
				.map(line -> line.substring("event:".length()).strip())
				.toList();
	}

	private int chatNumberStops() {
		Integer stops = jdbc.sql("select coalesce(sum(chat_number_stops), 0) from daily_stats")
				.query(Integer.class).single();
		return stops == null ? 0 : stops;
	}

	/**
	 * Every text-ish column in every table, searched for {@code needle}.
	 *
	 * <p>Driven from {@code information_schema} rather than a hand-written list, so a table
	 * added later is searched automatically. A hand-written list would pass for ever after
	 * someone adds the one column that does store chat text.
	 */
	private List<String> textColumnsContaining(String needle) {
		List<String> hits = new java.util.ArrayList<>();

		List<String> columns = jdbc.sql("""
				select table_name || '.' || column_name
				  from information_schema.columns
				 where table_schema = 'public'
				   and data_type in ('text', 'character varying', 'jsonb', 'json')
				""").query(String.class).list();

		for (String qualified : columns) {
			String table = qualified.substring(0, qualified.indexOf('.'));
			String column = qualified.substring(qualified.indexOf('.') + 1);
			Integer found = jdbc.sql("select count(*) from \"" + table + "\" where cast(\""
					+ column + "\" as text) like :needle")
					.param("needle", "%" + needle + "%")
					.query(Integer.class).single();
			if (found != null && found > 0) {
				hits.add(qualified);
			}
		}
		return hits;
	}

	/** The lipid sample, with the printed values and ranges the guard builds its set from. */
	private UUID lipidReport(UUID sessionId) {
		UUID id = fixtures.doneReport(sessionId, LocalDate.of(2026, 9, 28));

		fixtures.reportText(id, """
				Lipid Panel
				Total Cholesterol 238 mg/dL <200
				LDL Cholesterol 162 mg/dL <100
				HDL Cholesterol 52 mg/dL >40
				""");
		fixtures.biomarkerWithRange(id, 0, "Total Cholesterol", "total-cholesterol",
				"238", 238.0, "mg/dL", "<200", null, 200.0, Flag.HIGH);
		fixtures.biomarkerWithRange(id, 1, "LDL Cholesterol", "ldl-cholesterol",
				"162", 162.0, "mg/dL", "<100", null, 100.0, Flag.HIGH);
		fixtures.biomarkerWithRange(id, 2, "HDL Cholesterol", "hdl-cholesterol",
				"52", 52.0, "mg/dL", ">40", 40.0, null, Flag.NORMAL);
		return id;
	}
}
