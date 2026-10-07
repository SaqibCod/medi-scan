package dev.saq.mediscan.mask;

import java.util.Map;

/**
 * The outcome of masking one document.
 *
 * @param maskedText the only version of the text allowed past this point
 * @param counts how many spans of each type were replaced, for logging and stats
 * @param conflicts how many times a rule wanted to mask something inside a result row. Always
 *     zero in normal operation; a non-zero count means a rule is reaching into lab values and
 *     is worth investigating, which is why it is counted rather than silently dropped
 */
public record MaskResult(String maskedText, Map<MaskType, Integer> counts, int conflicts) {

	public MaskResult {
		counts = Map.copyOf(counts);
	}

	/** Total spans replaced. */
	public int totalMasked() {
		return counts.values().stream().mapToInt(Integer::intValue).sum();
	}

	/**
	 * Counts and conflicts only, never the text.
	 *
	 * <p>This record holds the text that is about to be sent to a model and stored, and its
	 * {@code toString} is the likeliest accidental route into a log line. The counts are what
	 * {@code LLD} 16 actually wants logged.
	 */
	@Override
	public String toString() {
		return "MaskResult[counts=" + counts + ", conflicts=" + conflicts
				+ ", chars=" + maskedText.length() + "]";
	}
}
