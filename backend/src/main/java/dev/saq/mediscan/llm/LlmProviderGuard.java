package dev.saq.mediscan.llm;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Refuses to start on an LLM configuration that cannot work, or should not.
 *
 * <p>Two failures this prevents, both of which are much worse discovered at the first upload
 * than at startup:
 *
 * <ul>
 * <li><strong>{@code gemini} with no API key.</strong> Every report would reach the provider,
 * fail, retry, and end as {@code LLM_UNAVAILABLE} - having spent six slots of the daily cap
 * per report on the way.</li>
 * <li><strong>{@code fake} in production.</strong> The fake provider returns scripted
 * answers. Running it outside a test or local profile would serve invented lab results to
 * whoever is reading them, which is the worst thing this application could do.</li>
 * </ul>
 *
 * <p>The key itself is never printed, not even its length ({@code CLAUDE.md}: no secrets in
 * logs).
 */
@Component
public class LlmProviderGuard {

	private static final Logger log = LoggerFactory.getLogger(LlmProviderGuard.class);

	private static final String GEMINI = "gemini";
	private static final String FAKE = "fake";

	/** The only profiles in which scripted model output is acceptable. */
	private static final Set<String> FAKE_ALLOWED_PROFILES = Set.of("local", "test");

	public LlmProviderGuard(MediScanProperties properties, Environment environment) {
		String provider = properties.llm().provider();
		List<String> activeProfiles = List.of(environment.getActiveProfiles());

		if (GEMINI.equalsIgnoreCase(provider)) {
			requireApiKey(properties);
		}
		else if (FAKE.equalsIgnoreCase(provider)) {
			requireTestOrLocalProfile(activeProfiles);
		}
		else {
			throw new IllegalStateException(
					"mediscan.llm.provider must be 'gemini' or 'fake', not '" + provider + "'");
		}

		log.info("LLM provider '{}' accepted for profiles {}", provider, activeProfiles);
	}

	private static void requireApiKey(MediScanProperties properties) {
		String apiKey = properties.llm().apiKey();
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException(
					"mediscan.llm.provider is 'gemini' but GEMINI_API_KEY is not set. "
							+ "Set it in .env, or set LLM_PROVIDER=fake for local development "
							+ "without a key.");
		}
	}

	private static void requireTestOrLocalProfile(List<String> activeProfiles) {
		boolean allowed = activeProfiles.stream().anyMatch(FAKE_ALLOWED_PROFILES::contains);
		if (!allowed) {
			throw new IllegalStateException(
					"mediscan.llm.provider is 'fake', which returns scripted results and must "
							+ "never serve real users. It is accepted only under the 'local' or "
							+ "'test' profiles; active profiles are " + activeProfiles + ".");
		}
	}
}
