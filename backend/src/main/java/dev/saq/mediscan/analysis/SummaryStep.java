package dev.saq.mediscan.analysis;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.llm.LlmGateway;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.llm.PromptTemplates;
import dev.saq.mediscan.report.Flag;

import tools.jackson.databind.json.JsonMapper;

/**
 * Step 2: ask the model to write the summary from the validated values.
 *
 * <p>The model gets the checked value list and nothing else - no report text, no ids, no
 * masked text ({@code CLAUDE.md} rule 5). That is what makes it structurally impossible for
 * the summary to contradict the flags, because the flags are the only thing it was shown.
 *
 * <p>Its output is then filtered again. The prompt asks for a highlight per out-of-range
 * marker, but a prompt is a request, not a guarantee: a highlight naming a marker that is not
 * flagged would tell a patient something is wrong when the data says otherwise, so those are
 * dropped in code.
 */
@Component
public class SummaryStep {

	private static final int MAX_OUTPUT_TOKENS = 1024;

	private final LlmGateway gateway;
	private final JsonMapper jsonMapper;
	private final int maxSummaryChars;
	private final int maxHighlights;

	public SummaryStep(LlmGateway gateway, JsonMapper jsonMapper, MediScanProperties properties) {
		this.gateway = gateway;
		this.jsonMapper = jsonMapper;
		this.maxSummaryChars = properties.llm().maxSummaryChars();
		this.maxHighlights = properties.llm().maxHighlights();
	}

	/** Runs step 2 and filters the result. */
	public ReportSummary run(List<ValidatedBiomarker> biomarkers) {
		ModelSummary model = gateway.structured(
				LlmPurpose.SUMMARY,
				PromptTemplates.wrap("results", toJson(biomarkers)),
				ModelSummary.class,
				0.0,
				MAX_OUTPUT_TOKENS,
				// A blank summary parses fine and is useless, so it counts as invalid output
				// and gets the one retry rather than being stored.
				summary -> summary.summary() != null && !summary.summary().isBlank());

		return new ReportSummary(
				truncate(model.summary().strip()),
				filterHighlights(model.highlights(), biomarkers));
	}

	/**
	 * The subset of each row the model is allowed to see.
	 *
	 * <p>Built as an explicit projection rather than serialising {@link ValidatedBiomarker},
	 * so adding a field to that record cannot silently widen what step 2 receives. There is no
	 * report id and no numeric value here: the model is summarising what was printed, and the
	 * flag already carries the judgement.
	 */
	private String toJson(List<ValidatedBiomarker> biomarkers) {
		List<SummaryInput> input = biomarkers.stream()
				.map(marker -> new SummaryInput(marker.testName(), marker.rawValue(),
						marker.unit(), marker.referenceRangeText(), marker.flag()))
				.toList();

		return jsonMapper.writeValueAsString(input);
	}

	/**
	 * Keeps only highlights that name a marker the code flagged {@code LOW} or {@code HIGH}.
	 *
	 * <p>Two things this prevents. A highlight about a normal marker would read as a warning
	 * about a value that is fine. And a highlight naming a test that is not on the report at
	 * all - which a model can produce from its own knowledge of what usually accompanies a
	 * panel - would be an invented finding.
	 */
	private List<String> filterHighlights(List<String> highlights,
			List<ValidatedBiomarker> biomarkers) {

		List<String> flaggedNames = biomarkers.stream()
				.filter(marker -> marker.flag() == Flag.LOW || marker.flag() == Flag.HIGH)
				.map(marker -> marker.testName().toLowerCase(Locale.ROOT))
				.toList();

		if (flaggedNames.isEmpty()) {
			// Nothing is out of range, so there is nothing to highlight, whatever the model
			// returned.
			return List.of();
		}

		return highlights.stream()
				.filter(highlight -> highlight != null && !highlight.isBlank())
				.map(String::strip)
				.filter(highlight -> mentionsFlaggedMarker(highlight, flaggedNames))
				.limit(maxHighlights)
				.toList();
	}

	private static boolean mentionsFlaggedMarker(String highlight, List<String> flaggedNames) {
		String lower = highlight.toLowerCase(Locale.ROOT);
		return flaggedNames.stream().anyMatch(lower::contains);
	}

	private String truncate(String summary) {
		return summary.length() <= maxSummaryChars
				? summary
				: summary.substring(0, maxSummaryChars).strip();
	}

	/**
	 * What step 2 produced, after filtering.
	 *
	 * @param patientSummary the summary text
	 * @param highlights one sentence per out-of-range marker
	 */
	public record ReportSummary(String patientSummary, List<String> highlights) {

		public ReportSummary {
			highlights = List.copyOf(highlights);
		}

		/** Hides the text, which quotes values and ranges. */
		@Override
		public String toString() {
			return "ReportSummary[summaryChars=" + patientSummary.length()
					+ ", highlights=" + highlights.size() + "]";
		}
	}

	/** The projection sent to the model. Deliberately not {@link ValidatedBiomarker}. */
	private record SummaryInput(String testName, String rawValue, String unit,
			String referenceRangeText, Flag flag) {
	}
}
