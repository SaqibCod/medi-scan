package dev.saq.mediscan.config;

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
	private static final String FAKE_PROVIDER = "fake";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String provider = environment.getProperty(PROVIDER_PROPERTY);
		if (!FAKE_PROVIDER.equalsIgnoreCase(provider)) {
			return;
		}

		// An explicit setting wins. Someone deliberately pointing the fake provider at a real
		// chat model - to compare them side by side, say - should not be silently overridden.
		if (environment.containsProperty(SPRING_AI_CHAT_PROPERTY)) {
			return;
		}

		// Added last so anything with higher precedence (command line, system properties)
		// still takes effect.
		environment.getPropertySources().addLast(new MapPropertySource(
				"mediscanFakeLlmProvider",
				Map.of(SPRING_AI_CHAT_PROPERTY, "none")));
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
