package dev.saq.mediscan.mask;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The regex rules, and the order they run in ({@code LLD} 10.3).
 *
 * <p><strong>Order is precision, highest first.</strong> The masker gives a span to whichever
 * rule claims it first, so a rule that knows exactly what it is looking at has to run before
 * one that is guessing. An SSN is unmistakable; a capitalised word after {@code Patient:} is
 * very likely a name; a capitalised word anywhere is a guess. Running them in that order means
 * the guess only ever gets the text nothing better explained.
 *
 * <p>The patterns are deliberately narrow in one specific way: none of them matches a bare
 * date or a bare number. Lab reports are mostly dates and numbers, and the two the pipeline
 * depends on - the specimen collection date and every lab value - must survive untouched. So
 * dates are only masked next to a birth-date label, and identifiers only next to an
 * identifier label.
 */
public final class MaskRules {

	// --- Social security numbers -------------------------------------------

	/**
	 * {@code 123-45-6789}.
	 *
	 * <p>Hyphenated only. A bare nine-digit run is far more likely to be an accession number
	 * or a specimen id, and those are the identifier rule's job - masking them here would
	 * report the wrong type and, worse, would also match a nine-digit stretch of a result row.
	 */
	private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

	// --- Email --------------------------------------------------------------

	private static final Pattern EMAIL = Pattern.compile(
			"\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

	// --- Identifiers --------------------------------------------------------

	/**
	 * A label, then the token after it.
	 *
	 * <p>Group 1 is the token, so the label survives: {@code MRN: A1234567} becomes
	 * {@code MRN: [ID]}, which keeps the line's structure legible to the extraction model.
	 *
	 * <p>The token has to contain at least one digit. Without that, {@code Patient ID: Not
	 * Provided} would mask the word {@code Not}, and more importantly a label followed by a
	 * word is usually a label followed by a word.
	 */
	private static final Pattern IDENTIFIER = Pattern.compile(
			"(?:medical\\s+record\\s+(?:no|number|#)|mrn|patient\\s+id|patient\\s+no"
					+ "|account(?:\\s+(?:no|number|#))?|accession(?:\\s+(?:no|number|#))?"
					+ "|specimen\\s+id|specimen\\s+no|requisition(?:\\s+(?:no|number|#))?"
					+ "|order(?:\\s+(?:no|number|#))|lab\\s+(?:no|id)|chart\\s+(?:no|number)"
					+ "|visit\\s+(?:no|number)|encounter\\s+(?:id|no))"
					// Space and tab only, never \s: a label must not reach across a newline
					// to claim the first token of the next line.
					+ "[ \\t]*[:#]?[ \\t]*"
					// A lookahead for "contains a digit somewhere", then the token itself.
					// Written this way because real identifiers put the digits anywhere:
					// A1234567, LV-7781, NC-4471829, 20260928-0114.
					+ "(?=[A-Za-z0-9-]*\\d)([A-Za-z0-9][A-Za-z0-9-]*)",
			Pattern.CASE_INSENSITIVE);

	// --- Birth dates --------------------------------------------------------

	/**
	 * A birth-date label, then a date within a short distance.
	 *
	 * <p>Group 1 is the date. The label stays, so the masked text still says a birth date was
	 * there - which the extraction model needs in order <em>not</em> to read the next date it
	 * sees as the collection date.
	 *
	 * <p>The gap allows for column layout between label and value but is bounded, so a
	 * {@code DOB} label cannot reach across the page and swallow the collection date. That is
	 * the single most costly false positive in this file: losing the collection date silently
	 * removes the report from every trend chart.
	 */
	private static final Pattern BIRTH_DATE = Pattern.compile(
			"(?:date\\s+of\\s+birth|birth\\s*date|d\\.?o\\.?b\\.?|born)"
					+ "[ \\t]*[:]?[ \\t]{0,30}"
					+ "("
					+ "\\d{4}-\\d{1,2}-\\d{1,2}"
					+ "|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}"
					+ "|\\d{1,2}\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{2,4}"
					+ "|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2},?\\s+\\d{2,4}"
					+ ")",
			Pattern.CASE_INSENSITIVE);

	// --- Phone numbers ------------------------------------------------------

	/**
	 * A phone number in the common separated forms.
	 *
	 * <p>Requires separators or parentheses. An unseparated ten-digit run is indistinguishable
	 * from an accession number, and a pattern loose enough to catch it also matches stretches
	 * of a result row - which the protected-span check would then have to catch, turning every
	 * report into a pile of logged conflicts.
	 */
	private static final Pattern PHONE = Pattern.compile(
			"(?<![\\d-])"
					+ "(?:\\+?\\d{1,2}[\\s.-]*)?"
					+ "(?:\\(\\d{3}\\)|\\d{3})"
					+ "[\\s.-]+\\d{3}[\\s.-]+\\d{4}"
					+ "(?![\\d-])");

	// --- Labelled names -----------------------------------------------------

	/**
	 * A name after a label that says a name follows.
	 *
	 * <p>Group 1 is the name: one to four capitalised words, allowing initials, hyphens,
	 * apostrophes and a trailing credential like {@code MD} being excluded by the word limit.
	 *
	 * <p>This is the rule that does the real work, and the reason the pipeline does not depend
	 * on a statistical model. Lab reports label their people - {@code Patient Name:},
	 * {@code Ordered by:}, {@code Physician:} - and a label is evidence in a way that
	 * capitalisation is not.
	 */
	/**
	 * One part of a name: a capitalised word, or an initial with its period.
	 *
	 * <p>Both alternatives are needed, and the order matters. A single {@code [A-Z]} followed
	 * by an optional period would greedily take the first letter of the next word, turning
	 * {@code Alan Whitfield} into {@code Alan W} - which masks six characters and leaves
	 * {@code hitfield} behind in the stored text.
	 */
	private static final String NAME_PART = "(?:[A-Z][A-Za-z'’\\-]+|[A-Z]\\.)";

	/**
	 * One to four name parts, separated by a single space.
	 *
	 * <p>A single space, not {@code \s+}, and this is load-bearing. Lab reports are column
	 * layouts: with {@code \s+} the name in {@code Patient Name: Marcus T. Elder} runs on
	 * through thirteen spaces of column gap and swallows {@code Accession} from the next
	 * column - and across a newline into the following line. The resulting span then overlaps
	 * a protected result row, so the masker rejects it, and the name is left in the text
	 * entirely. Restricting the separator keeps each name inside its own column.
	 */
	private static final String NAME_GROUP =
			"(" + NAME_PART + "(?:[ ]" + NAME_PART + "){0,3})";

	/**
	 * An honorific, matched before the name group so it is not part of it.
	 *
	 * <p>{@code Ordered by: Dr. Alan Whitfield} has to become
	 * {@code Ordered by: Dr. [NAME]}: keeping the honorific tells a reader a clinician was
	 * removed rather than the patient.
	 */
	private static final String HONORIFIC =
			"(?:(?i:dr|doctor|mr|mrs|ms|miss|prof|professor)\\.?[ \\t]+)?";

	/**
	 * Note the absence of {@link Pattern#CASE_INSENSITIVE}. The label needs to be
	 * case-insensitive and the name must not be: with the flag applied to the whole pattern,
	 * {@code [A-Z]} matches lowercase too, and {@code Patient Name: not recorded} masks the
	 * word {@code not}. The label alternation carries its own inline {@code (?i:...)} instead.
	 */
	private static final Pattern LABELLED_NAME = Pattern.compile(
			"(?i:patient\\s+name|patient|name|ordered\\s+by|order(?:ing)?\\s+(?:physician|provider)"
					+ "|referred\\s+by|referring\\s+(?:physician|provider)|physician|doctor"
					+ "|provider|signed\\s+by|verified\\s+by|reported\\s+by|attending"
					+ "|collected\\s+by|performed\\s+by|technologist|pathologist)"
					// The colon is required, which matters for more than tidiness. Without
					// it the alternation backtracks: `Patient Name: not recorded` fails on
					// the long label, retries with the short `patient` label, and masks the
					// word `Name`. Requiring the colon makes the label unambiguous, and
					// lab reports punctuate their header blocks this way.
					//
					// The cost is a name in a colon-less column layout, which this rule then
					// misses. That is a known false negative, recorded in SECURITY.md.
					+ "[ \\t]*:[ \\t]{0,20}"
					+ HONORIFIC
					+ NAME_GROUP);

	/**
	 * A name after an honorific, anywhere.
	 *
	 * <p>Separate from the labelled rule because {@code Dr.} is itself the label. Group 1 is
	 * the name only, so {@code Dr. Alan Whitfield} becomes {@code Dr. [NAME]} - keeping the
	 * honorific makes it obvious to a reader that a clinician's name was removed, not a
	 * patient's.
	 */
	private static final Pattern HONORIFIC_NAME = Pattern.compile(
			"\\b(?:Dr|Doctor|Mr|Mrs|Ms|Miss|Prof|Professor)\\.?[ \\t]+" + NAME_GROUP);

	private MaskRules() {
	}

	/**
	 * The regex rules in precision order.
	 *
	 * <p>{@code OpenNlpNameRule} is not here: it is optional, configured, and appended by
	 * {@link Masker} so that this list stays the set of rules that always run.
	 */
	public static List<MaskRule> ordered() {
		return List.of(
				new RegexMaskRule("ssn", SSN, MaskType.SSN),
				new RegexMaskRule("email", EMAIL, MaskType.EMAIL),
				new RegexMaskRule("identifier", IDENTIFIER, MaskType.ID, 1),
				new RegexMaskRule("birthDate", BIRTH_DATE, MaskType.DOB, 1),
				new RegexMaskRule("phone", PHONE, MaskType.PHONE),
				new RegexMaskRule("labelledName", LABELLED_NAME, MaskType.NAME, 1),
				new RegexMaskRule("honorificName", HONORIFIC_NAME, MaskType.NAME, 1));
	}
}
