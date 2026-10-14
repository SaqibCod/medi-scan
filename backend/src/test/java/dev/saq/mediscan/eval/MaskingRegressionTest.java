package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import dev.saq.mediscan.mask.MaskResult;
import dev.saq.mediscan.mask.Masker;
import dev.saq.mediscan.mask.OpenNlpNameRule;
import dev.saq.mediscan.report.ReportStatus;

/**
 * Runs the real {@link Masker} over every {@code DONE} case's input text, with no model and no
 * database ({@code docs/phase-3-lld.md} section 9, item 2). Two things must hold for every
 * case:
 *
 * <ul>
 * <li>every planted personal-data string of an in-scope type is gone from the masked text
 * ({@code ADDRESS} is excluded - LLD decision E6, the known gap);</li>
 * <li>every expected row's {@code testName}, {@code rawValue}, and (when printed) {@code unit}
 * still appear - masking must never damage a lab value.</li>
 * </ul>
 *
 * <p>{@link #KNOWN_MISSES} starts empty. Per the LLD, an entry is added only with explicit
 * approval, each with a reason and the issue it points at - never silently, and never to make a
 * score look better than the masking actually is.
 */
class MaskingRegressionTest {

	/** Label rules only - the production default ({@code mediscan.mask.opennlp-enabled=false}). */
	private final Masker masker = new Masker(noOpenNlpRule());

	/**
	 * Approved, explained exceptions to "every planted string of an in-scope type must be
	 * absent from the masked text". Both entries were reviewed and approved after the first run
	 * of this test found them; neither is a new bug - both are the documented
	 * "no label, no honorific" limitation in {@code SECURITY.md}'s known-limits section.
	 */
	private static final Set<KnownMiss> KNOWN_MISSES = Set.of(
			new KnownMiss("pii-names-everywhere", "Victor Hammond",
					"appears only after \"Attending:\" (header) and \"Reviewed by\" (footer), "
							+ "neither of which is in LabeledNameRule's label list",
					"SECURITY.md known limit: names with no recognized label"),
			new KnownMiss("pii-names-everywhere", "Grace Liu",
					"the labelled occurrence (\"Patient Name:\") is masked correctly; a second, "
							+ "unlabelled mid-text mention (\"Note: Grace Liu was seen...\") survives",
					"SECURITY.md known limit: names with no label and no honorific"));

	private record KnownMiss(String caseId, String text, String reason, String issue) {
	}

	@Test
	void maskingLeavesNoInScopePersonalDataAndDamagesNoLabValue() {
		List<String> unexplainedLeaks = new ArrayList<>();
		List<String> damagedRows = new ArrayList<>();
		List<String> integrityFailures = new ArrayList<>();

		for (EvalDatasetLoader.Entry entry : EvalDatasetLoader.loadAll()) {
			EvalCase evalCase = entry.evalCase();
			if (evalCase.expectedStatus() != ReportStatus.DONE) {
				continue;
			}

			MaskResult result;
			try {
				result = masker.mask(evalCase.reportText());
			}
			catch (RuntimeException ex) {
				integrityFailures.add(evalCase.id() + ": " + ex.getClass().getSimpleName());
				continue;
			}
			String maskedText = result.maskedText();

			List<ExpectedResult.PlantedPii> inScope = entry.expected().pii().stream()
					.filter(p -> p.type() != ExpectedResult.PiiType.ADDRESS)
					.toList();
			var haystacks = List.of(new MaskingLeakCheck.Haystack("masked_text", maskedText));

			for (MaskingLeakCheck.Leak leak : MaskingLeakCheck.findLeaks(inScope, haystacks)) {
				KnownMiss asKnown = new KnownMiss(evalCase.id(), leak.text(), "", "");
				boolean approved = KNOWN_MISSES.stream()
						.anyMatch(k -> k.caseId().equals(asKnown.caseId())
								&& k.text().equals(asKnown.text()));
				if (!approved) {
					unexplainedLeaks.add(
							"case=" + evalCase.id() + " type=" + leak.type() + " text=\"" + leak.text() + "\"");
				}
			}

			for (ExpectedResult.ExpectedBiomarker row : entry.expected().biomarkers()) {
				if (!maskedText.contains(row.testName())) {
					damagedRows.add("case=" + evalCase.id() + " testName=\"" + row.testName() + "\"");
				}
				if (!maskedText.contains(row.rawValue())) {
					damagedRows.add("case=" + evalCase.id() + " rawValue=\"" + row.rawValue() + "\"");
				}
				if (row.unit() != null && !maskedText.contains(row.unit())) {
					damagedRows.add("case=" + evalCase.id() + " unit=\"" + row.unit() + "\"");
				}
			}
		}

		assertThat(integrityFailures).as("masking integrity check failures").isEmpty();
		assertThat(damagedRows).as("lab values or units damaged by masking").isEmpty();
		assertThat(unexplainedLeaks).as("personal data not caught by masking (not in KNOWN_MISSES)")
				.isEmpty();
	}

	private static ObjectProvider<OpenNlpNameRule> noOpenNlpRule() {
		return new ObjectProvider<>() {
			@Override
			public OpenNlpNameRule getObject() {
				throw new UnsupportedOperationException();
			}

			@Override
			public OpenNlpNameRule getObject(Object... args) {
				throw new UnsupportedOperationException();
			}

			@Override
			public OpenNlpNameRule getIfAvailable() {
				return null;
			}

			@Override
			public OpenNlpNameRule getIfUnique() {
				return null;
			}
		};
	}
}
