package dev.saq.mediscan.analysis;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.llm.LlmGateway;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.llm.PromptTemplates;

/**
 * Step 1: ask the model to transcribe the printed results.
 *
 * <p>Thin by design. The schema is strings only, the text goes in delimited, and the result is
 * handed straight to {@link BiomarkerValidator} - this class exists so the one place that
 * builds an extraction request is obvious, not to add logic between the two.
 */
@Component
public class ExtractionStep {

	/** Enough for a long panel in JSON; past this the model has stopped transcribing. */
	private static final int MAX_OUTPUT_TOKENS = 4096;

	private final LlmGateway gateway;

	public ExtractionStep(LlmGateway gateway) {
		this.gateway = gateway;
	}

	/**
	 * Runs step 1 against the masked text.
	 *
	 * <p>Masked text only. This is the one model call that sees the report, and it sees it
	 * after masking ({@code CLAUDE.md} rule 1).
	 */
	public ModelExtraction run(String maskedText) {
		return gateway.structured(
				LlmPurpose.EXTRACT,
				// Delimited, because report text is data and never instructions
				// (CLAUDE.md rule 6). The system prompt says so too.
				PromptTemplates.wrap("report", maskedText),
				ModelExtraction.class,
				// Transcription has nothing for sampling to improve.
				0.0,
				MAX_OUTPUT_TOKENS);
	}
}
