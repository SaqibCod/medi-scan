package dev.saq.mediscan.llm;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Supplies the {@code com.google.genai.Client} bean with a real per-call HTTP timeout.
 *
 * <p>Spring AI's own autoconfiguration builds this same bean type with no timeout at all -
 * confirmed from the Google GenAI SDK's bytecode (1.65.0): {@code HttpOptions} is the only
 * place a timeout can be set on this client, and nothing in Spring AI's Google GenAI module
 * exposes a property for it. Without one, a single hung call blocks a job-pool worker
 * indefinitely (observed 2026-10-18: over an hour, no response, nothing to time it out) - with
 * only two workers ({@code backend/CLAUDE.md}, "Job pool"), two such hangs stall every user.
 *
 * <p>{@code mediscan.llm.timeout} already existed for exactly this ("per-attempt timeout") but
 * was dead code - bound and validated, never read anywhere. This is what finally reads it.
 *
 * <p>Spring AI's own {@code Client} bean is {@code @ConditionalOnMissingBean}, so defining one
 * here is enough to replace it outright; no need to also exclude the autoconfiguration.
 */
@Configuration
@ConditionalOnProperty(name = "mediscan.llm.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiClientConfig {

	@Bean
	Client googleGenAiClient(MediScanProperties properties) {
		HttpOptions httpOptions = HttpOptions.builder()
				.timeout((int) properties.llm().timeout().toMillis())
				.build();

		return Client.builder()
				.apiKey(properties.llm().apiKey())
				.httpOptions(httpOptions)
				.build();
	}

	/**
	 * Points the embedding model at the same API key and the same timed-out client (phase 5).
	 *
	 * <p>Spring AI's own {@code GoogleGenAiEmbeddingConnectionAutoConfiguration} would
	 * otherwise build this. Two reasons not to let it.
	 *
	 * <p><strong>It demands a Google Cloud project unless an API key is set on its own
	 * property.</strong> Its logic is: use {@code spring.ai.google.genai.embedding.api-key} if
	 * it has text, otherwise assert a {@code project-id} and a {@code location} - the Vertex AI
	 * path, which needs a billing account and breaks the $0 constraint. Supplying the bean here
	 * means one key, {@code GEMINI_API_KEY}, configures both chat and embeddings, instead of a
	 * second property that silently falls back to Vertex when someone forgets it.
	 *
	 * <p><strong>Its client would have no timeout.</strong> That is the same hole this class
	 * was written to close for chat: {@code HttpOptions} is the only place a timeout can be set
	 * on the Google SDK client, and nothing in Spring AI exposes a property for it. Passing the
	 * client built above gives embedding calls the same per-call timeout, so a hung embedding
	 * cannot hold a chat request open until the stream times out.
	 *
	 * <p>The autoconfiguration's factory method is {@code @ConditionalOnMissingBean}, so
	 * defining this is enough to replace it outright.
	 */
	@Bean
	GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(Client client,
			MediScanProperties properties) {

		return GoogleGenAiEmbeddingConnectionDetails.builder()
				.apiKey(properties.llm().apiKey())
				.genAiClient(client)
				.build();
	}
}
