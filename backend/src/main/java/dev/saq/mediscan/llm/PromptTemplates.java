package dev.saq.mediscan.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the versioned prompt files.
 *
 * <p>Prompts live in {@code src/main/resources/prompts/} with the version in the filename, and
 * are never built by concatenating strings in Java ({@code backend/CLAUDE.md}, "Pipeline
 * rules"). Two reasons that matters here: a prompt change is the single most likely cause of
 * an extraction regression, so it has to be visible as a file diff and attributable to a
 * version; and the injection defence depends on the instructions being fixed text that no
 * report content can reach.
 *
 * <p>Loaded once at startup, and startup fails if a prompt is missing - a report that reached
 * the model with an empty system prompt would produce confident nonsense rather than an error.
 */
@Component
public class PromptTemplates {

	private static final String DIRECTORY = "prompts/";

	/** The current version of each prompt. Bump the filename, not the contents. */
	private static final Map<LlmPurpose, String> FILES = Map.of(
			LlmPurpose.EXTRACT, "extract-v1.txt",
			LlmPurpose.SUMMARY, "summary-v1.txt");

	private final Map<LlmPurpose, String> prompts = new EnumMap<>(LlmPurpose.class);
	private final Map<LlmPurpose, String> versions = new EnumMap<>(LlmPurpose.class);

	public PromptTemplates() {
		for (LlmPurpose purpose : LlmPurpose.values()) {
			String file = FILES.get(purpose);
			if (file == null) {
				throw new IllegalStateException("no prompt file declared for " + purpose);
			}
			prompts.put(purpose, read(file));
			// "extract-v1.txt" -> "extract-v1", which is what the usage log records.
			versions.put(purpose, file.replaceFirst("\\.txt$", ""));
		}
	}

	/** The system prompt for {@code purpose}. */
	public String systemPrompt(LlmPurpose purpose) {
		return prompts.get(purpose);
	}

	/** The prompt version, for the usage record and for correlating an eval run. */
	public String version(LlmPurpose purpose) {
		return versions.get(purpose);
	}

	/**
	 * Wraps data in delimiters for the user turn.
	 *
	 * <p>The delimiters are not decoration. Report text is data, not instructions
	 * ({@code CLAUDE.md} rule 6), and the system prompt tells the model that everything inside
	 * these tags is to be transcribed rather than obeyed. Wrapping happens here so no caller
	 * can forget it.
	 */
	public static String wrap(String tag, String content) {
		return "<" + tag + ">\n" + content + "\n</" + tag + ">";
	}

	private static String read(String file) {
		ClassPathResource resource = new ClassPathResource(DIRECTORY + file);
		if (!resource.exists()) {
			throw new IllegalStateException("missing prompt file " + DIRECTORY + file);
		}
		try (InputStream in = resource.getInputStream()) {
			String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
			if (content.isEmpty()) {
				throw new IllegalStateException("prompt file " + file + " is empty");
			}
			return content;
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read prompt file " + file, ex);
		}
	}
}
