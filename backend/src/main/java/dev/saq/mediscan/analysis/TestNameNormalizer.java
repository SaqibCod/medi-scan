package dev.saq.mediscan.analysis;

import java.util.Locale;

/**
 * Normalises a test name for matching.
 *
 * <p>One implementation, used for three things that must agree: the {@code test_name_norm}
 * column, the biomarker catalogue lookup, and the duplicate check in validation. If the
 * catalogue normalised differently from the column, a marker would get a slug on one report
 * and not on the next, and phase 6's trends would treat them as different tests.
 *
 * <p>Lowercase, punctuation to spaces, runs of whitespace collapsed. Punctuation becomes a
 * space rather than being deleted so {@code HDL-Cholesterol} and {@code HDL Cholesterol}
 * normalise alike, while {@code T4} does not become {@code t 4}.
 */
public final class TestNameNormalizer {

	private TestNameNormalizer() {
	}

	/** The normalised form; empty string for null or blank input. */
	public static String normalize(String testName) {
		if (testName == null) {
			return "";
		}
		return testName
				.toLowerCase(Locale.ROOT)
				// Anything that is not a letter or a digit is a separator. Keeps "t4" intact
				// while making "hdl-c" and "hdl c" the same thing.
				.replaceAll("[^a-z0-9]+", " ")
				.strip()
				.replaceAll("\\s+", " ");
	}
}
