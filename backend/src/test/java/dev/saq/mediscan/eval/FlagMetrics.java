package dev.saq.mediscan.eval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.saq.mediscan.report.Flag;

/**
 * Flag-correctness metrics over matched rows ({@code docs/phase-3-lld.md} section 6.3).
 *
 * <p>Flags come from code, not the model, so an error here points at a range or value parsing
 * problem, or at a wrong {@code referenceRangeText} the model transcribed - never at the model
 * guessing a flag, since it is never asked for one.
 */
public final class FlagMetrics {

	private FlagMetrics() {
	}

	/** Expected {@code HIGH}/{@code LOW} produced {@code NORMAL}, or the reverse - never zero. */
	public record DangerousError(String caseId, String testName, Flag expectedFlag,
			Flag producedFlag) {
	}

	public record Summary(
			double flagAccuracy,
			Map<Flag, Map<Flag, Integer>> confusionMatrix,
			List<DangerousError> dangerousErrors,
			double unknownRate) {
	}

	public static Summary summarize(List<RowMatcher.ScoredMatch> matches) {
		Map<Flag, Map<Flag, Integer>> confusion = emptyConfusionMatrix();
		List<DangerousError> dangerous = new ArrayList<>();
		int correct = 0;
		int expectedComputable = 0;
		int computableButProducedUnknown = 0;

		for (RowMatcher.ScoredMatch scored : matches) {
			Flag expected = scored.match().expected().flag();
			Flag produced = scored.match().produced().flag();

			confusion.get(expected).merge(produced, 1, Integer::sum);

			if (expected == produced) {
				correct++;
			}
			if (isDangerous(expected, produced)) {
				dangerous.add(new DangerousError(scored.caseId(),
						scored.match().expected().testName(), expected, produced));
			}
			if (expected != Flag.UNKNOWN) {
				expectedComputable++;
				if (produced == Flag.UNKNOWN) {
					computableButProducedUnknown++;
				}
			}
		}

		return new Summary(
				rate(correct, matches.size()),
				confusion,
				dangerous,
				// Of the rows the key says were computable, how many came back UNKNOWN anyway -
				// the cost of being careful, reported separately from outright errors.
				rate(computableButProducedUnknown, expectedComputable));
	}

	/** Expected {@code HIGH}/{@code LOW} called {@code NORMAL}, or expected {@code NORMAL}
	 * called {@code HIGH}/{@code LOW}. {@code UNKNOWN} on either side is cautious, not dangerous. */
	private static boolean isDangerous(Flag expected, Flag produced) {
		boolean expectedAbnormal = expected == Flag.HIGH || expected == Flag.LOW;
		boolean producedAbnormal = produced == Flag.HIGH || produced == Flag.LOW;

		if (expectedAbnormal && produced == Flag.NORMAL) {
			return true;
		}
		return expected == Flag.NORMAL && producedAbnormal;
	}

	private static Map<Flag, Map<Flag, Integer>> emptyConfusionMatrix() {
		Map<Flag, Map<Flag, Integer>> matrix = new EnumMap<>(Flag.class);
		for (Flag row : Flag.values()) {
			matrix.put(row, new EnumMap<>(Flag.class));
		}
		return matrix;
	}

	private static double rate(int numerator, int denominator) {
		return denominator == 0 ? 0.0 : (double) numerator / denominator;
	}
}
