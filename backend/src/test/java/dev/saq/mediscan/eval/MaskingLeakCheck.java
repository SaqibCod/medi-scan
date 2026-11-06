package dev.saq.mediscan.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Looks for a planted personal-data string inside text that is supposed to be clean of it
 * ({@code docs/phase-3-lld.md} section 6.4).
 *
 * <p>Three rules beyond a plain case-insensitive substring check:
 *
 * <ul>
 * <li><strong>Phones compare by digits only,</strong> so {@code (555) 123-4567} and
 * {@code 555.123.4567} are the same leak.</li>
 * <li><strong>Names are also checked part by part</strong> (first name, last name), ignoring
 * parts shorter than 3 characters, because a label rule can mask "Jane Roe" as a whole phrase
 * and still leave a bare "Roe" in a footer.</li>
 * <li><strong>{@code ADDRESS} is still detected,</strong> not skipped - LLD decision E6 only
 * excludes it from the zero-leak target, which is a scoring decision for the caller, not a
 * reason to stop looking.</li>
 * </ul>
 */
public final class MaskingLeakCheck {

	private static final int MIN_NAME_PART_LENGTH = 3;
	private static final int MIN_PHONE_DIGITS = 7;

	private MaskingLeakCheck() {
	}

	/** One planted string found in one place it should not be. */
	public record Leak(ExpectedResult.PiiType type, String text, String where) {
	}

	/**
	 * @param haystacks every place to check, labelled for reporting (for example
	 *     {@code "masked_text"}, {@code "llm_request[extract]"}, {@code "report_summary"});
	 *     a {@code null} entry's text is treated as absent
	 */
	public static List<Leak> findLeaks(List<ExpectedResult.PlantedPii> planted,
			List<Haystack> haystacks) {

		List<Leak> leaks = new ArrayList<>();
		for (ExpectedResult.PlantedPii item : planted) {
			for (Haystack haystack : haystacks) {
				if (leaksInto(item, haystack.text())) {
					leaks.add(new Leak(item.type(), item.text(), haystack.label()));
					break;
				}
			}
		}
		return leaks;
	}

	/** A labelled place to search, so a leak can be reported with where it was found. */
	public record Haystack(String label, String text) {
	}

	private static boolean leaksInto(ExpectedResult.PlantedPii item, String haystack) {
		if (haystack == null || haystack.isEmpty()) {
			return false;
		}

		if (item.type() == ExpectedResult.PiiType.PHONE) {
			String plantedDigits = digitsOnly(item.text());
			return plantedDigits.length() >= MIN_PHONE_DIGITS
					&& digitsOnly(haystack).contains(plantedDigits);
		}

		String lowerHaystack = haystack.toLowerCase(Locale.ROOT);
		if (lowerHaystack.contains(item.text().toLowerCase(Locale.ROOT))) {
			return true;
		}

		if (item.type() == ExpectedResult.PiiType.NAME) {
			for (String part : item.text().split("\\s+")) {
				if (part.length() >= MIN_NAME_PART_LENGTH
						&& lowerHaystack.contains(part.toLowerCase(Locale.ROOT))) {
					return true;
				}
			}
		}
		return false;
	}

	private static String digitsOnly(String text) {
		return text.replaceAll("\\D", "");
	}
}
