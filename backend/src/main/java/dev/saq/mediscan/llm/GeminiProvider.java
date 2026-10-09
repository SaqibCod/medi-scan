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
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
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
 * would mean paying for prose responses that have to be retried.
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
		BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputType);

		GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) GoogleGenAiChatOptions.builder()
				.responseMimeType(JSON_MIME_TYPE)
				.responseSchema(converter.getJsonSchema())
				.maxOutputTokens(request.maxOutputTokens())
				.temperature(request.temperature())
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
	 */
	private static LlmException classify(RuntimeException ex) {
		Throwable cursor = ex;
		while (cursor != null) {
			if (cursor instanceof org.springframework.web.client.HttpStatusCodeException statusError) {
				int status = statusError.getStatusCode().value();
				// 429 and 5xx may pass; everything else in the 4xx range is a bad key, a bad
				// request, or a withdrawn model, and will fail identically next time.
				boolean retryable = status == 429 || status >= 500;
				return retryable
						? new LlmException.TransientLlmException("provider returned " + status, ex)
						: new LlmException.PermanentLlmException("provider returned " + status, ex);
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
			// than transient: retrying the identical prompt will do the same thing.
			throw new LlmException.InvalidLlmOutputException("provider returned empty content");
		}
		return text;
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
