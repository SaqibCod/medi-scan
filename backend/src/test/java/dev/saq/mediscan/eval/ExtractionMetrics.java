package dev.saq.mediscan.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.saq.mediscan.analysis.ValidatedBiomarker;
import dev.saq.mediscan.report.ReportStatus;

/**
 * Extraction-accuracy metrics over a batch of {@link CaseOutcome}s ({@code docs/phase-3-lld.md}
 * section 6.2).
 *
 * <p>Row-level metrics (recall, precision, unit/range/numeric accuracy) only ever look at cases
 * that both expect and reach {@code DONE}: a case that should fail has no rows to match, and a
 * case that was supposed to succeed but didn't is counted once, in
 * {@link Summary#failureRateOnValidReports()}, not blamed for zero row matches on top of that.
 */
public final class ExtractionMetrics {

	private ExtractionMetrics() {
	}

	public record MissedRow(String caseId, ExpectedResult.ExpectedBiomarker expected) {
	}

	public record ExtraRow(String caseId, ValidatedBiomarker produced) {
	}

	public record Summary(
			int totalCases,
			int totalExpectedRows,
			int totalProducedRows,
			int totalMatched,
			double rowRecall,
			double rowPrecision,
			double unitAccuracy,
			double rangeAccuracy,
			double numericAccuracy,
			double dateAccuracy,
			double statusAccuracy,
			double failureRateOnValidReports,
			List<RowMatcher.ScoredMatch> matches,
			List<MissedRow> missed,
			List<ExtraRow> extra) {
	}

	public static Summary summarize(List<CaseOutcome> outcomes) {
		int totalExpectedRows = 0;
		int totalProducedRows = 0;
		List<RowMatcher.ScoredMatch> allMatches = new ArrayList<>();
		List<MissedRow> allMissed = new ArrayList<>();
		List<ExtraRow> allExtra = new ArrayList<>();

		int dateCorrect = 0;
		int doneCases = 0;
		int statusCorrect = 0;
		int shouldBeDoneCases = 0;
		int shouldBeDoneButFailed = 0;

		for (CaseOutcome outcome : outcomes) {
			if (hasExpectedStatusAndCode(outcome)) {
				statusCorrect++;
			}

			boolean expectedDone = outcome.evalCase().expectedStatus() == ReportStatus.DONE;
			if (!expectedDone) {
				continue;
			}
			shouldBeDoneCases++;

			if (outcome.actualStatus() != ReportStatus.DONE) {
				shouldBeDoneButFailed++;
				continue;
			}
			doneCases++;

			if (Objects.equals(outcome.actualCollectedOn(), outcome.expected().collectedOn())) {
				dateCorrect++;
			}

			totalExpectedRows += outcome.expected().biomarkers().size();
			totalProducedRows += outcome.producedRows().size();

			String caseId = outcome.evalCase().id();
			RowMatcher.Result result =
					RowMatcher.match(outcome.expected().biomarkers(), outcome.producedRows());
			result.matches().forEach(m -> allMatches.add(new RowMatcher.ScoredMatch(caseId, m)));
			result.missed().forEach(m -> allMissed.add(new MissedRow(caseId, m)));
			result.extra().forEach(e -> allExtra.add(new ExtraRow(caseId, e)));
		}

		int unitCorrect = 0;
		int rangeCorrect = 0;
		int numericCorrect = 0;
		for (RowMatcher.ScoredMatch scored : allMatches) {
			RowMatcher.Match match = scored.match();
			if (Objects.equals(match.expected().unit(), match.produced().unit())) {
				unitCorrect++;
			}
			if (Objects.equals(match.expected().refLow(), match.produced().refLow())
					&& Objects.equals(match.expected().refHigh(), match.produced().refHigh())) {
				rangeCorrect++;
			}
			if (Objects.equals(match.expected().numericValue(), match.produced().numericValue())) {
				numericCorrect++;
			}
		}

		int matched = allMatches.size();
		return new Summary(
				outcomes.size(),
				totalExpectedRows,
				totalProducedRows,
				matched,
				rate(matched, totalExpectedRows),
				rate(matched, totalProducedRows),
				rate(unitCorrect, matched),
				rate(rangeCorrect, matched),
				rate(numericCorrect, matched),
				rate(dateCorrect, doneCases),
				rate(statusCorrect, outcomes.size()),
				rate(shouldBeDoneButFailed, shouldBeDoneCases),
				allMatches, allMissed, allExtra);
	}

	private static boolean hasExpectedStatusAndCode(CaseOutcome outcome) {
		if (outcome.actualStatus() != outcome.evalCase().expectedStatus()) {
			return false;
		}
		return Objects.equals(outcome.actualErrorCode(), outcome.evalCase().expectedErrorCode());
	}

	private static double rate(int numerator, int denominator) {
		return denominator == 0 ? 0.0 : (double) numerator / denominator;
	}
}
