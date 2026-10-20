package dev.saq.mediscan.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.report.Flag;

/**
 * Turns what the model transcribed into values the application is willing to show.
 *
 * <p>This is where {@code CLAUDE.md} rule 4 is enforced, and the support check is the heart of
 * it: every {@code rawValue} must appear in the masked source text as a whole token, or the
 * row is dropped. A model cannot then invent a plausible result, nor quietly correct one it
 * thinks is wrong - the only values that reach a patient are ones that were printed on their
 * report.
 *
 * <p>Everything numeric is derived here from those strings. Nothing numeric is read from the
 * model, because there is nothing numeric in the schema to read.
 *
 * <p>Dropping a row is always preferred to guessing at it. A short result list is visibly
 * incomplete; a wrong value is not.
 */
@Component
public class BiomarkerValidator {

	/** Longer than any real test name or value; past this the model has returned prose. */
	private static final int MAX_FIELD_LENGTH = 200;

	/**
	 * A reference range is one printed field; it should never legitimately contain one of
	 * these. Traced 2026-10-18 (Phase 3 eval, {@code range-styles} case): Gemini occasionally
	 * replaces a printed en dash or comparator ({@code –}, {@code ≥}) with a literal embedded
	 * newline while transcribing a range, which {@link RangeParser} then correctly refuses to
	 * read as a number - but without this check, the corrupted text itself (not just the
	 * flag) would still reach the biomarker and be shown to the reader as if it were printed.
	 */
	private static final Pattern RANGE_CONTROL_CHARACTER = Pattern.compile("\\p{Cntrl}");

	private final BiomarkerCatalog catalog;
	private final CollectedDateParser dateParser;
	private final int maxRows;

	public BiomarkerValidator(BiomarkerCatalog catalog, CollectedDateParser dateParser,
			MediScanProperties properties) {

		this.catalog = catalog;
		this.dateParser = dateParser;
		this.maxRows = properties.llm().maxRows();
	}

	/**
	 * Validates one extraction against the text it came from.
	 *
	 * @param extraction what the model returned
	 * @param maskedText the text the model was given, for the support check
	 */
	public ValidationOutcome validate(ModelExtraction extraction, String maskedText) {
		Map<ValidationOutcome.DropReason, Integer> drops =
				new EnumMap<>(ValidationOutcome.DropReason.class);

		List<ValidatedBiomarker> kept = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int[] rangeTextDiscarded = { 0 };

		for (ModelExtraction.ModelRow row : extraction.biomarkers()) {
			if (kept.size() >= maxRows) {
				// A report with hundreds of rows is a parsing failure, not a long report.
				count(drops, ValidationOutcome.DropReason.OVER_LIMIT);
				continue;
			}

			Optional<ValidatedBiomarker> validated =
					validateRow(row, maskedText, kept.size(), seen, drops, rangeTextDiscarded);
			validated.ifPresent(kept::add);
		}

		LocalDate collectedOn = dateParser.parse(extraction.collectedOn(), maskedText).orElse(null);

		return new ValidationOutcome(kept, collectedOn, drops, rangeTextDiscarded[0]);
	}

	private Optional<ValidatedBiomarker> validateRow(ModelExtraction.ModelRow row,
			String maskedText, int position, Set<String> seen,
			Map<ValidationOutcome.DropReason, Integer> drops, int[] rangeTextDiscarded) {

		String testName = trimToNull(row.testName());
		String rawValue = trimToNull(row.rawValue());

		if (testName == null || rawValue == null
				|| testName.length() > MAX_FIELD_LENGTH || rawValue.length() > MAX_FIELD_LENGTH) {
			count(drops, ValidationOutcome.DropReason.MALFORMED);
			return Optional.empty();
		}

		if (!appearsInText(rawValue, maskedText)) {
			// The model invented or altered this value. The one check that makes the rest of
			// the pipeline trustworthy.
			count(drops, ValidationOutcome.DropReason.UNSUPPORTED);
			return Optional.empty();
		}

		String unit = trimToNull(row.unit());
		String referenceRangeText = sanitizeReferenceRange(row.referenceRangeText(), maskedText);
		if (referenceRangeText == null && row.referenceRangeText() != null
				&& !row.referenceRangeText().isBlank()) {
			rangeTextDiscarded[0]++;
		}
		String testNameNorm = TestNameNormalizer.normalize(testName);

		// Same test, same value, same unit: the model listed one row twice, which happens
		// when a report repeats a panel across pages.
		String fingerprint = testNameNorm + "|" + rawValue + "|" + (unit == null ? "" : unit);
		if (!seen.add(fingerprint)) {
			count(drops, ValidationOutcome.DropReason.DUPLICATE);
			return Optional.empty();
		}

		ParsedValue value = ValueParser.parse(rawValue);
		Optional<Range> range = RangeParser.parse(referenceRangeText);
		Flag flag = FlagCalculator.calculate(value, range);

		return Optional.of(new ValidatedBiomarker(
				position,
				testName,
				testNameNorm,
				catalog.findSlug(testName).orElse(null),
				rawValue,
				value.storedNumber(),
				unit,
				referenceRangeText,
				range.map(Range::low).orElse(null),
				range.map(Range::high).orElse(null),
				flag));
	}

	/**
	 * Whether {@code rawValue} appears in {@code maskedText} as a whole token.
	 *
	 * <p>Whole-token matching matters: a substring search would accept {@code 5.4} because the
	 * text contains {@code 15.42}, which is exactly the kind of near-miss a model produces when
	 * it misreads a column. The boundaries therefore exclude an adjacent digit or decimal
	 * point, while still allowing the value to sit next to spaces, brackets or a unit.
	 *
	 * <p>Whitespace inside the value is normalised on both sides, because PDF extraction turns
	 * {@code 1,200} into {@code 1 200} unpredictably.
	 */
	private static boolean appearsInText(String rawValue, String maskedText) {
		if (maskedText == null || maskedText.isEmpty()) {
			return false;
		}

		String needle = rawValue.strip().replaceAll("\\s+", " ");
		if (needle.isEmpty()) {
			return false;
		}

		// Build the pattern from the escaped value, allowing any run of whitespace wherever
		// the value has a space.
		String escaped = Pattern.quote(needle).replace(" ", "\\E\\s+\\Q");
		Pattern wholeToken = Pattern.compile("(?<![\\w.])" + escaped + "(?![\\w.])");

		String haystack = maskedText.replaceAll("[ \\t]+", " ");
		return wholeToken.matcher(haystack).find();
	}

	/**
	 * Clears a reference range that looks corrupted rather than storing or parsing it.
	 *
	 * <p>Two checks, same reasoning as {@link #appearsInText}: dropping is always preferred to
	 * showing a reader something the report did not actually print.
	 *
	 * <p>The control-character check runs on the model's raw answer, before any trimming -
	 * {@link String#strip()} removes a <em>leading</em> control character along with ordinary
	 * whitespace, which would erase the one piece of evidence that the field was corrupted
	 * (observed: a printed {@code ≥60} transcribed as a bare {@code "\n60"}, where {@code .strip()}
	 * alone would leave a plausible-looking {@code "60"} with no comparator - wrong, but no
	 * longer visibly wrong). The support check then catches the other shape of the same bug,
	 * where the corruption lands in the middle rather than at an edge (a printed {@code 1.7 – 2.2}
	 * transcribed as {@code "1.7 \n 2.2"}: no control character survives trimming here either,
	 * but the collapsed text no longer matches the source as a whole token, because the dash is
	 * simply gone).
	 *
	 * <p>Genuinely qualitative range text ({@code "Negative"}, {@code "See comment"}, a
	 * sex-dependent range with a colon and a slash) is untouched: it is real, it was printed, and
	 * it passes the support check exactly as {@code rawValue} does. This only discards text that
	 * either could not have come from the report as printed, or demonstrably did not.
	 */
	private static String sanitizeReferenceRange(String rawRangeText, String maskedText) {
		if (rawRangeText == null) {
			return null;
		}
		if (RANGE_CONTROL_CHARACTER.matcher(rawRangeText).find()) {
			return null;
		}

		String trimmed = trimToNull(rawRangeText);
		if (trimmed == null) {
			return null;
		}

		return appearsInText(trimmed, maskedText) ? trimmed : null;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static void count(Map<ValidationOutcome.DropReason, Integer> drops,
			ValidationOutcome.DropReason reason) {

		drops.merge(reason, 1, Integer::sum);
	}
}
