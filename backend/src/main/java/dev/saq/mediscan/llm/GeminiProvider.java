package dev.saq.mediscan.llm;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.MarkdownCodeBlockCleaner;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Gemini, through Spring AI's Google GenAI module and a Google AI Studio API key.
 *
 * <p>The API-key route, not Vertex AI: {@code spring.ai.google.genai.api-key} selects the
 * Gemini Developer API, which needs no Google Cloud project. Vertex AI would need a project and
 * billing account, which breaks the $0 constraint in {@code docs/plan.md} section 1.
 *
 * <p><strong>Structured output, twice over.</strong> The JSON schema generated from the target
 * record is sent as Gemini's native {@code responseSchema} with
 * {@code responseMimeType=application/json}, and the reply is then parsed by the same
 * {@link BeanOutputConverter} that produced the schema. The native schema makes malformed
 * output rare; the converter is what notices when it happens anyway. Relying on the schema
 * alone would mean trusting the provider to be perfect, and relying on the converter alone
 * would mean paying for prose responses that have to be retried. The converter also runs a
 * {@link MarkdownCodeBlockCleaner} before parsing, as cheap insurance against a markdown-fenced
 * reply - though the one real failure traced on {@code gemini-2.5-flash} (Phase 3 eval,
 * 2026-10-16: every summary call for {@code boundary-values}, {@code cbc-clean-table},
 * {@code cmp-clean-table}, {@code comparators}, {@code iron-vitamins} failing {@code
 * EXTRACTION_FAILED}) turned out to be {@code finishReason=MAX_TOKENS} truncating valid,
 * unfenced JSON mid-object: that model's invisible reasoning tokens were eating the
 * output-token budget before the visible answer could finish. The cleaner stays as a second
 * line of defence for a cause that has not actually been observed yet.
 *
 * <p><strong>Model migration, 2026-10-18.</strong> {@code gemini-2.5-flash} started returning
 * {@code 404 This model ... is no longer available to new users} partway through a Phase 3 eval
 * run - Google retiring it mid-session, not a bug here. Moved to {@code gemini-3.8-flash}, the
 * model that 404 pointed to. That model line dropped {@code thinkingBudget} (which could turn
 * thinking fully off) in favour of {@code thinkingLevel}, whose lowest setting ({@code LOW})
 * still reasons a little - confirmed by a direct call against the exact content that triggered
 * the 2.5-flash truncation above: {@code finishReason=STOP} comfortably inside today's
 * {@code maxOutputTokens} budgets, so no budget increase was needed to make room for it.
 *
 * <p><strong>Why the model is set per request.</strong> That migration did not actually take
 * effect for two more days, and the reason is worth keeping: this class used to leave the model
 * to the autoconfigured {@code ChatModel} bean's defaults, which {@code application.yml}
 * configured through {@code spring.ai.google.genai.chat.options.*} - a path Spring AI 2.0
 * deprecated. Deprecated keys still <em>bind</em>, so every configuration check looked right,
 * but the autoconfiguration no longer applies them: the real model fell back to the library
 * default ({@code gemini-2.5-flash}) regardless of {@code GEMINI_CHAT_MODEL}, while
 * {@link #model()} - which feeds every usage log line and the eval's cache key - went on
 * reporting the configured one. Two sources of truth, one of them silently decorative. Setting
 * the model on the request from {@code mediscan.llm.model} collapses them into one, so the
 * logged model is true by construction. Full trace: {@code docs/phase-3-findings.md} item 1.
 *
 * <p>A thin adapter on purpose. It builds a request, parses a response, and sorts its failures
 * into the three {@link LlmException} kinds. The daily cap, retries and usage logging all
 * belong to {@link LlmGateway}, so this class cannot get the budget accounting wrong.
 */
@Component
@ConditionalOnProperty(name = "mediscan.llm.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiProvider implements LlmProvider {

	private static final String JSON_MIME_TYPE = "application/json";

	private final ChatModel chatModel;
	private final String model;

	public GeminiProvider(ChatModel chatModel, MediScanProperties properties) {
		this.chatModel = chatModel;
		this.model = properties.llm().model();
	}

	@Override
	public String id() {
		return "gemini";
	}

	@Override
	public String model() {
		return model;
	}

	@Override
	public <T> LlmResult<T> structured(LlmRequest request, Class<T> outputType) {
		// Defensive against a markdown-fenced reply, though the traced failure (see class
		// javadoc) was unfenced JSON truncated by MAX_TOKENS, not fencing.
		BeanOutputConverter<T> converter =
				new BeanOutputConverter<>(outputType, null, new MarkdownCodeBlockCleaner());

		GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) GoogleGenAiChatOptions.builder()
				// Set per request, on purpose: see the class javadoc. Relying on the bean's
				// default options meant the model was whatever Spring AI's autoconfiguration
				// had resolved, which silently was not the configured one - while this
				// provider's own model() (the usage log, the eval cache key) kept claiming it
				// was. Setting it here makes mediscan.llm.model the single source of truth and
				// the logged model true by construction.
				.model(model)
				.responseMimeType(JSON_MIME_TYPE)
				.responseSchema(converter.getJsonSchema())
				.maxOutputTokens(request.maxOutputTokens())
				.temperature(request.temperature())
				// Gemini 3.x has no full thinking-off switch, so LOW is the floor - verified
				// to finish with STOP well inside today's output budgets for both extraction
				// and summary content. NOTE: thinkingLevel and thinkingBudget are mutually
				// exclusive, and the 2.5 series accepts only thinkingBudget: pointing
				// GEMINI_CHAT_MODEL back at a 2.5-series model makes this line throw
				// IllegalArgumentException on every call (classified PERMANENT below, so it
				// fails fast rather than burning retries).
				.thinkingLevel(GoogleGenAiThinkingLevel.LOW)
				.build();

		List<Message> messages = List.of(
				new SystemMessage(request.systemPrompt()),
				new UserMessage(request.userContent()));

		long startedAt = System.nanoTime();
		ChatResponse response = call(new Prompt(messages, options));
		long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

		String text = textOf(response);
		T value = parse(converter, text);

		return new LlmResult<>(value, inputTokens(response), outputTokens(response), latencyMs);
	}

	private ChatResponse call(Prompt prompt) {
		try {
			return chatModel.call(prompt);
		}
		catch (RuntimeException ex) {
			throw classify(ex);
		}
	}

	/**
	 * Sorts a provider exception into retryable or not.
	 *
	 * <p>By exception type and HTTP status rather than by message text: messages differ
	 * between Spring AI versions, and matching on them would silently reclassify every error
	 * on an upgrade. Anything unrecognised is treated as transient, because one wasted retry
	 * is cheaper than failing a report that would have succeeded.
	 *
	 * <p><strong>The Google SDK's errors are not Spring's.</strong>
	 * {@code HttpStatusCodeException} is a {@code RestTemplate} type, but this module talks to
	 * Gemini over the Google GenAI SDK's own OkHttp client, which throws
	 * {@link com.google.genai.errors.ApiException} and
	 * {@link com.google.genai.errors.GenAiIOException} instead. Until those branches existed,
	 * every Google API error fell through to the transient fallback at the bottom - so a
	 * withdrawn model (404) and a request the client itself rejected were both retried as if
	 * the provider were merely having a bad minute, and reported as {@code LLM_UNAVAILABLE}.
	 */
	private static LlmException classify(RuntimeException ex) {
		Throwable cursor = ex;
		while (cursor != null) {
			if (cursor instanceof org.springframework.web.client.HttpStatusCodeException statusError) {
				return byStatus(statusError.getStatusCode().value(), ex);
			}
			// Covers both of the SDK's response types: ClientException (4xx) and
			// ServerException (5xx) share this public supertype and its code().
			if (cursor instanceof com.google.genai.errors.ApiException apiError) {
				return byStatus(apiError.code(), ex);
			}
			if (cursor instanceof com.google.genai.errors.GenAiIOException) {
				// The SDK's wrapper for transport failures, including the OkHttp call timeout
				// that GeminiClientConfig installs (an InterruptedIOException, which is not a
				// SocketTimeoutException and so is not matched below).
				return new LlmException.TransientLlmException(
						"provider call failed: " + cursor.getClass().getSimpleName(), ex);
			}
			if (cursor instanceof IllegalArgumentException) {
				// Rejected client-side while building the request - an option the configured
				// model does not accept, for example. Entirely deterministic, so retrying
				// would spend the daily budget proving that three times over. Only the class
				// name travels: request-validation text can quote the request, and the request
				// is the masked report.
				return new LlmException.PermanentLlmException(
						"provider rejected the request as invalid: "
								+ cursor.getClass().getSimpleName(), ex);
			}
			if (cursor instanceof java.net.SocketTimeoutException
					|| cursor instanceof java.util.concurrent.TimeoutException
					|| cursor instanceof java.net.ConnectException
					|| cursor instanceof java.net.UnknownHostException) {
				return new LlmException.TransientLlmException(
						"provider call failed: " + cursor.getClass().getSimpleName(), ex);
			}
			cursor = cursor.getCause();
		}

		// No message from the exception: a provider error body can quote the prompt back, and
		// the prompt is the masked report.
		return new LlmException.TransientLlmException(
				"provider call failed: " + ex.getClass().getSimpleName(), ex);
	}

	/**
	 * Splits an HTTP status into retryable or not.
	 *
	 * <p>429 and 5xx may pass; everything else in the 4xx range is a bad key, a bad request, or
	 * a withdrawn model, and will fail identically next time. Only the status travels into the
	 * message - never the provider's error body, which can quote the prompt back.
	 */
	private static LlmException byStatus(int status, RuntimeException ex) {
		boolean retryable = status == 429 || status >= 500;
		return retryable
				? new LlmException.TransientLlmException("provider returned " + status, ex)
				: new LlmException.PermanentLlmException("provider returned " + status, ex);
	}

	private static String textOf(ChatResponse response) {
		if (response == null) {
			throw new LlmException.InvalidLlmOutputException("provider returned no response");
		}
		Generation generation = response.getResult();
		if (generation == null) {
			throw new LlmException.InvalidLlmOutputException("provider returned no candidate");
		}
		AssistantMessage output = generation.getOutput();
		String text = output != null ? output.getText() : null;
		if (text == null || text.isBlank()) {
			// A blank response is usually a safety filter or a token limit. Invalid rather
			// than transient: retrying the identical prompt will do the same thing. The finish
			// reason - a short, fixed token from the provider ("SAFETY", "MAX_TOKENS", "STOP",
			// ...), never response content - says which, so it is safe to carry in the message
			// (LlmException's own contract: "a fixed description, never the response body").
			throw new LlmException.InvalidLlmOutputException(
					"provider returned empty content (finishReason=" + finishReasonOf(generation) + ")");
		}
		return text;
	}

	/** @return the provider's finish reason, or {@code "unknown"} when none is available. */
	private static String finishReasonOf(Generation generation) {
		var metadata = generation.getMetadata();
		String reason = metadata != null ? metadata.getFinishReason() : null;
		return reason == null || reason.isBlank() ? "unknown" : reason;
	}

	private static <T> T parse(BeanOutputConverter<T> converter, String text) {
		try {
			T value = converter.convert(text);
			if (value == null) {
				throw new LlmException.InvalidLlmOutputException("response parsed to null");
			}
			return value;
		}
		catch (LlmException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// Deliberately not including the response text, which is the model's rendering of
			// the report.
			throw new LlmException.InvalidLlmOutputException(
					"response was not valid JSON for the requested schema", ex);
		}
	}

	private static int inputTokens(ChatResponse response) {
		Usage usage = usageOf(response);
		return usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
	}

	private static int outputTokens(ChatResponse response) {
		Usage usage = usageOf(response);
		return usage != null && usage.getCompletionTokens() != null
				? usage.getCompletionTokens() : 0;
	}

	private static Usage usageOf(ChatResponse response) {
		return response.getMetadata() != null ? response.getMetadata().getUsage() : null;
	}
}
