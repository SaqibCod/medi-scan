package dev.saq.mediscan.llm;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

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
}
