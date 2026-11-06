package dev.saq.mediscan.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;

import dev.saq.mediscan.support.TestProperties;

/**
 * What the provider puts in a request, and how it sorts the provider's failures.
 *
 * <p>Both halves exist because of the same incident (Phase 3, 2026-10-20,
 * {@code docs/phase-3-findings.md} item 1). The model was never set on the request, so every
 * call went to whichever model Spring AI's autoconfiguration had resolved - silently not the
 * configured one - while the usage log and the eval cache key both claimed otherwise. And the
 * resulting client-side rejection was classified transient, so a deterministic
 * misconfiguration was retried as though the provider were having a bad minute.
 *
 * <p>The stub throws instead of answering: these tests are about the request and the
 * classification, not about parsing, and {@code ChatModel} has exactly one abstract method, so
 * no mocking framework is needed.
 */
class GeminiProviderTest {

	/** A trivial output type; nothing here gets as far as parsing. */
	record Answer(String text) {
	}

	private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();

	private GeminiProvider providerFailingWith(RuntimeException failure) {
		ChatModel chatModel = prompt -> {
			lastPrompt.set(prompt);
			throw failure;
		};
		return new GeminiProvider(chatModel, TestProperties.defaults());
	}

	private void call(GeminiProvider provider) {
		provider.structured(
				new LlmRequest(LlmPurpose.EXTRACT, "system", "<report>x</report>", 0.0, 1024),
				Answer.class);
	}

	// --- the request ---------------------------------------------------------

	@Test
	@DisplayName("sets the configured model on every request, not the bean's default")
	void setsConfiguredModelOnEveryRequest() {
		GeminiProvider provider = providerFailingWith(new GenAiIOException("boom"));

		assertThatThrownBy(() -> call(provider)).isInstanceOf(LlmException.class);

		// The regression that matters: without this, the model is whatever the autoconfigured
		// ChatModel bean defaulted to, and provider.model() below is a separate claim that
		// nothing enforces.
		assertThat(lastPrompt.get().getOptions().getModel()).isEqualTo("fake-model");
		assertThat(provider.model()).isEqualTo("fake-model");
	}

	// --- classification ------------------------------------------------------

	@Test
	@DisplayName("a 4xx from the Google SDK is permanent, so it is never retried")
	void clientErrorIsPermanent() {
		// A withdrawn model answers 404 every time. Retrying spends the daily budget proving
		// it twice more. ClientException is not Spring's HttpStatusCodeException, which is why
		// this used to fall through to the transient fallback.
		GeminiProvider provider =
				providerFailingWith(new ClientException(404, "NOT_FOUND", "model is gone"));

		assertThatThrownBy(() -> call(provider))
				.isInstanceOf(LlmException.PermanentLlmException.class)
				.hasMessage("provider returned 404");
	}

	@Test
	@DisplayName("a 429 is transient, because the next minute may well pass")
	void rateLimitingIsTransient() {
		GeminiProvider provider =
				providerFailingWith(new ClientException(429, "RESOURCE_EXHAUSTED", "slow down"));

		assertThatThrownBy(() -> call(provider))
				.isInstanceOf(LlmException.TransientLlmException.class)
				.hasMessage("provider returned 429");
	}

	@Test
	@DisplayName("a 5xx is transient")
	void serverErrorIsTransient() {
		GeminiProvider provider =
				providerFailingWith(new ServerException(503, "UNAVAILABLE", "try later"));

		assertThatThrownBy(() -> call(provider))
				.isInstanceOf(LlmException.TransientLlmException.class)
				.hasMessage("provider returned 503");
	}

	@Test
	@DisplayName("a transport failure is transient, including the client's own call timeout")
	void transportFailureIsTransient() {
		// What GeminiClientConfig's OkHttp callTimeout surfaces as: an InterruptedIOException
		// wrapped by the SDK, which is not a SocketTimeoutException.
		GeminiProvider provider = providerFailingWith(
				new GenAiIOException("Failed to execute HTTP request.",
						new java.io.InterruptedIOException("timeout")));

		assertThatThrownBy(() -> call(provider))
				.isInstanceOf(LlmException.TransientLlmException.class);
	}

	@Test
	@DisplayName("a request the client rejected is permanent, not a provider outage")
	void rejectedRequestIsPermanent() {
		// Spring AI validates some options against the model before sending anything - for
		// example thinkingLevel against a 2.5-series model. Deterministic, so it must fail
		// fast rather than burn three attempts and report LLM_UNAVAILABLE.
		GeminiProvider provider = providerFailingWith(new RuntimeException(
				"Failed to generate content",
				new IllegalArgumentException("ThinkingLevel.LOW is not supported for model 'x'")));

		assertThatThrownBy(() -> call(provider))
				.isInstanceOf(LlmException.PermanentLlmException.class)
				.hasMessage("provider rejected the request as invalid: IllegalArgumentException");
	}

	@Test
	@DisplayName("an unrecognised failure stays transient")
	void unrecognisedFailureStaysTransient() {
		// One wasted retry is cheaper than failing a report that would have succeeded.
		GeminiProvider provider = providerFailingWith(new IllegalStateException("who knows"));

		assertThatThrownBy(() -> call(provider))
				.isInstanceOf(LlmException.TransientLlmException.class)
				.hasMessage("provider call failed: IllegalStateException");
	}

	@Test
	@DisplayName("no classified message carries the provider's error text")
	void messagesNeverCarryProviderText() {
		// A provider error body can quote the request back, and the request is the masked
		// report (CLAUDE.md rule 3). Every message above is a fixed description plus a status
		// or a class name, which is what lets LlmUsageLogger print it.
		String canary = "ZZCANARY-Name-7391";
		GeminiProvider provider =
				providerFailingWith(new ClientException(400, "INVALID_ARGUMENT", canary));

		assertThatThrownBy(() -> call(provider))
				.isInstanceOf(LlmException.PermanentLlmException.class)
				.hasMessageNotContaining(canary);
	}
}
