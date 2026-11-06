package dev.saq.mediscan.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns Spring AI's chat autoconfiguration off when the fake LLM provider is selected.
 *
 * <p>Spring AI's Google GenAI autoconfiguration is gated on
 * {@code spring.ai.model.chat=google-genai} with {@code matchIfMissing=true}, and its client
 * factory throws at startup unless an API key or a Google Cloud project is configured. Tests
 * and local development run against {@code FakeLlmProvider} and have neither, so without this
 * the context fails before a single test runs.
 *
 * <p>The alternative was to make developers set {@code LLM_PROVIDER=fake} <em>and</em>
 * {@code spring.ai.model.chat=none}, two switches that mean one thing and break confusingly
 * when they disagree. {@code mediscan.llm.provider} stays the only switch, exactly as
 * {@code LLD} section 5 describes it, and this translates it.
 *
 * <p>Runs at {@link Ordered#LOWEST_PRECEDENCE} so Boot's config data - and therefore
 * {@code application.yml} and the active profiles - is already loaded and
 * {@code mediscan.llm.provider} can be read.
 *
 * <p>Registered in {@code META-INF/spring.factories}; {@code @Component} would be far too
 * late, since autoconfiguration conditions are evaluated before beans are created.
 */
public class LlmProviderEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	private static final String PROVIDER_PROPERTY = "mediscan.llm.provider";
	private static final String SPRING_AI_CHAT_PROPERTY = "spring.ai.model.chat";

	/**
	 * Phase 5. Note the {@code .text} suffix: Spring AI gates the Google text-embedding
	 * autoconfiguration on {@code spring.ai.model.embedding.text}, not on
	 * {@code spring.ai.model.embedding}. Setting the shorter key would look right, change
	 * nothing, and leave the real embedding client trying to build itself without a key.
	 */
	private static final String SPRING_AI_EMBEDDING_PROPERTY = "spring.ai.model.embedding.text";

	/**
	 * The embedding <em>connection</em> autoconfiguration, which the property above does not
	 * reach.
	 *
	 * <p>It is gated only on the Google SDK being on the classpath, so it runs even with the
	 * embedding model switched off - and it asserts a Google Cloud {@code project-id} whenever
	 * its own API key property is blank, which under the fake provider it always is. The result
	 * is a context that refuses to start with "Google GenAI project-id must be set!" in a test
	 * suite that was never going to call Google. Excluding it is the narrowest fix: the real
	 * provider supplies this bean itself from {@code GeminiClientConfig}.
	 */
	private static final String EMBEDDING_CONNECTION_AUTOCONFIGURATION =
			"org.springframework.ai.model.google.genai.autoconfigure.embedding."
					+ "GoogleGenAiEmbeddingConnectionAutoConfiguration";

	private static final String EXCLUDE_PROPERTY = "spring.autoconfigure.exclude";

	private static final String FAKE_PROVIDER = "fake";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String provider = environment.getProperty(PROVIDER_PROPERTY);
		if (!FAKE_PROVIDER.equalsIgnoreCase(provider)) {
			return;
		}

		Map<String, Object> disabled = new LinkedHashMap<>();

		// An explicit setting wins, for either kind. Someone deliberately pointing the fake
		// provider at a real chat model - to compare them side by side, say - should not be
		// silently overridden.
		if (!environment.containsProperty(SPRING_AI_CHAT_PROPERTY)) {
			disabled.put(SPRING_AI_CHAT_PROPERTY, "none");
		}
		if (!environment.containsProperty(SPRING_AI_EMBEDDING_PROPERTY)) {
			disabled.put(SPRING_AI_EMBEDDING_PROPERTY, "none");
		}
		if (!environment.containsProperty(EXCLUDE_PROPERTY)) {
			// Set rather than appended: nothing else in this application excludes an
			// autoconfiguration, and a property source cannot merge into a list anyway - the
			// highest-precedence source wins the whole value. If an exclusion is ever added
			// elsewhere, this has to become part of that list rather than a second source.
			disabled.put(EXCLUDE_PROPERTY, EMBEDDING_CONNECTION_AUTOCONFIGURATION);
		}
		if (disabled.isEmpty()) {
			return;
		}

		// Added last so anything with higher precedence (command line, system properties)
		// still takes effect.
		environment.getPropertySources().addLast(new MapPropertySource(
				"mediscanFakeLlmProvider", disabled));
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
