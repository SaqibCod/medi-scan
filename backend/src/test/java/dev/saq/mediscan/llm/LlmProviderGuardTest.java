package dev.saq.mediscan.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.support.TestProperties;

/**
 * The startup guard on LLM configuration.
 *
 * <p>A plain unit test, because the thing being tested is a constructor that throws. The two
 * failures it prevents are both ones that would otherwise surface at the first upload, by
 * which point a report has been accepted and the budget partly spent.
 */
class LlmProviderGuardTest {

	@Test
	@DisplayName("gemini with a key starts")
	void geminiWithKeyStarts() {
		assertThatCode(() -> guard(TestProperties.withLlmProvider("gemini", "a-real-key"), "prod"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("gemini with no key refuses to start, and says what to do")
	void geminiWithoutKeyFails() {
		assertThatThrownBy(() -> guard(TestProperties.withLlmProvider("gemini", ""), "prod"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GEMINI_API_KEY")
				.hasMessageContaining("LLM_PROVIDER=fake");
	}

	@Test
	@DisplayName("gemini with a blank key refuses to start")
	void geminiWithBlankKeyFails() {
		assertThatThrownBy(() -> guard(TestProperties.withLlmProvider("gemini", "   "), "prod"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GEMINI_API_KEY");
	}

	@Test
	@DisplayName("the failure message never contains the key")
	void messageNeverContainsKey() {
		String key = "AIzaSyTOTALLY-NOT-A-REAL-KEY";

		// The only reason this guard reads the key at all is to check it is non-blank, so a
		// message quoting it would be a pure liability.
		assertThatCode(() -> guard(TestProperties.withLlmProvider("gemini", key), "prod"))
				.doesNotThrowAnyException();

		assertThatThrownBy(() -> guard(TestProperties.withLlmProvider("unknown", key), "prod"))
				.isInstanceOf(IllegalStateException.class)
				.satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(key));
	}

	@Test
	@DisplayName("fake starts under the local and test profiles")
	void fakeStartsUnderLocalAndTest() {
		assertThatCode(() -> guard(TestProperties.withLlmProvider("fake", ""), "local"))
				.doesNotThrowAnyException();
		assertThatCode(() -> guard(TestProperties.withLlmProvider("fake", ""), "test"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("fake refuses to start with no profile, which is how production runs")
	void fakeFailsWithoutProfile() {
		// The fake provider returns scripted results. Serving those to a real reader would be
		// the worst thing this application could do, so it is a startup failure, not a warning.
		assertThatThrownBy(() -> guard(TestProperties.withLlmProvider("fake", "")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("scripted");
	}

	@Test
	@DisplayName("fake refuses to start under an unrelated profile")
	void fakeFailsUnderOtherProfile() {
		assertThatThrownBy(() -> guard(TestProperties.withLlmProvider("fake", ""), "prod"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("'local' or 'test'");
	}

	@Test
	@DisplayName("an unknown provider refuses to start")
	void unknownProviderFails() {
		assertThatThrownBy(() -> guard(TestProperties.withLlmProvider("openai", "key"), "prod"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must be 'gemini' or 'fake'");
	}

	private static LlmProviderGuard guard(MediScanProperties properties, String... profiles) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles);
		return new LlmProviderGuard(properties, environment);
	}
}
