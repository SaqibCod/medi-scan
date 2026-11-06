package dev.saq.mediscan.eval;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dev.saq.mediscan.analysis.TestNameNormalizer;
import dev.saq.mediscan.analysis.ValidatedBiomarker;

/**
 * Pairs one case's produced rows with its expected rows ({@code docs/phase-3-lld.md} section
 * 6.1).
 *
 * <p>A produced row matches an expected row when {@code test_name_norm} is equal (via the same
 * {@link TestNameNormalizer} production uses) and {@code rawValue} is equal. Matching is greedy
 * in report order: each expected row claims the first still-unclaimed produced row that
 * qualifies, so a repeated test with two different values ({@code repeated-test}) pairs
 * correctly without any extra bookkeeping.
 */
public final class RowMatcher {

	private RowMatcher() {
	}

	/** One expected row paired with the produced row judged to be the same result. */
	public record Match(ExpectedResult.ExpectedBiomarker expected, ValidatedBiomarker produced) {
	}

	/**
	 * @param missed expected rows with no match - the extraction never produced them
	 * @param extra produced rows with no match - a wrong or invented row, despite every
	 *     {@code rawValue} having to appear in the masked text
	 */
	public record Result(List<Match> matches, List<ExpectedResult.ExpectedBiomarker> missed,
			List<ValidatedBiomarker> extra) {
	}

	public static Result match(List<ExpectedResult.ExpectedBiomarker> expected,
			List<ValidatedBiomarker> produced) {

		List<ValidatedBiomarker> remaining = new ArrayList<>(produced);
		List<Match> matches = new ArrayList<>();
		List<ExpectedResult.ExpectedBiomarker> missed = new ArrayList<>();

		for (ExpectedResult.ExpectedBiomarker exp : expected) {
			String expectedNorm = TestNameNormalizer.normalize(exp.testName());
			ValidatedBiomarker found = claimFirstMatch(remaining, expectedNorm, exp.rawValue());
			if (found != null) {
				matches.add(new Match(exp, found));
			}
			else {
				missed.add(exp);
			}
		}

		return new Result(matches, missed, remaining);
	}

	private static ValidatedBiomarker claimFirstMatch(List<ValidatedBiomarker> remaining,
			String expectedNorm, String expectedRawValue) {

		Iterator<ValidatedBiomarker> it = remaining.iterator();
		while (it.hasNext()) {
			ValidatedBiomarker candidate = it.next();
			if (candidate.testNameNorm().equals(expectedNorm)
					&& candidate.rawValue().equals(expectedRawValue)) {
				it.remove();
				return candidate;
			}
		}
		return null;
	}

	/** A match, labelled with the case it came from - what the metrics classes report by. */
	public record ScoredMatch(String caseId, Match match) {
	}
}
