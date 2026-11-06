package dev.saq.mediscan.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The model names configured in {@code application.yml}.
 *
 * <p>Two assertions, and both are about failures this project has actually had.
 *
 * <p>The first is drift. {@code GEMINI_CHAT_MODEL} has a default in two places - the Spring AI
 * property that configures the autoconfigured client, and {@code mediscan.llm.model}, which
 * {@code GeminiProvider} sets on every request and which is therefore the one that decides what
 * is really called. Both files say they are kept in step; nothing checked it. When they last
 * disagreed, the configured model bound correctly, was reported correctly by the usage log, and
 * was not the model being called, which took two days to see (phase-3-findings item 1).
 *
 * <p>The second is pinning the pipeline to a model that has stopped serving generation.
 * {@code gemini-3.8-flash} answers a models listing in under half a second and refuses every
 * {@code generateContent} call; because it was the default, a fresh checkout could not process a
 * single report. The list below is measured, not guessed (phase-4-findings item 6). A model on it
 * may well recover, so this is a prompt to re-measure rather than a permanent verdict - but
 * re-measuring is exactly the step whose absence cost four days.
 */
class ModelDefaultsTest {

	/**
	 * Models observed refusing every real generation call, with the date last checked.
	 *
	 * <p>Keyed by what the API is asked for, so an alias counts separately: {@code
	 * gemini-flash-latest} resolves into this line and fails the same way.
	 */
	private static final Set<String> NOT_SERVING_GENERATION =
			Set.of("gemini-3.8-flash", "gemini-3.7-flash", "gemini-flash-latest");

	private static final Pattern PIPELINE_MODEL_DEFAULT =
			Pattern.compile("\\$\\{GEMINI_CHAT_MODEL:([^}]*)}");

	@Test
	@DisplayName("the pipeline model is defaulted in two places and they agree")
	void pipelineModelDefaultsAgree() {
		List<String> defaults = pipelineModelDefaults();

		// One is the Spring AI client property, the other mediscan.llm.model. If this count
		// changes, the new occurrence needs to be in step too - or deleted.
		assertThat(defaults)
				.as("occurrences of ${GEMINI_CHAT_MODEL:...} in application.yml")
				.hasSize(2);
		assertThat(defaults)
				.as("the two defaults for the pipeline model must name the same model")
				.containsOnly(defaults.getFirst());
	}

	@Test
	@DisplayName("the pipeline model is not one that has stopped answering")
	void pipelineModelStillAnswers() {
		assertThat(pipelineModelDefaults())
				.as(
						"""
						These models answer a models listing but refuse every generation call, \
						so defaulting to one leaves a fresh checkout unable to process any \
						report. If one has recovered, re-measure it with a real generateContent \
						call, record the result in docs/phase-4-findings.md item 6, and take it \
						off the list here.""")
				.doesNotContainAnyElementsOf(NOT_SERVING_GENERATION);
	}

	private static List<String> pipelineModelDefaults() {
		Matcher matcher = PIPELINE_MODEL_DEFAULT.matcher(applicationYml());
		List<String> found = new ArrayList<>();
		while (matcher.find()) {
			found.add(matcher.group(1).trim());
		}
		return found;
	}

	/**
	 * The file as text, deliberately not as bound properties.
	 *
	 * <p>Binding would resolve the placeholder against whatever {@code GEMINI_CHAT_MODEL} the
	 * machine running the tests happens to export, which is the opposite of what this asserts:
	 * the question is what a checkout does with no environment set.
	 */
	private static String applicationYml() {
		try (var in = new ClassPathResource("application.yml").getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException("application.yml is not on the test classpath", e);
		}
	}
}
