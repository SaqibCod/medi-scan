package dev.saq.mediscan.mask;

/**
 * The kinds of personal data masking removes, and the placeholder each leaves behind.
 *
 * <p>A placeholder rather than deletion, for two reasons. It keeps the line's shape, so a
 * header block still looks like a header block to the extraction model instead of becoming a
 * run of orphaned colons. And it makes masking visible: a reader of the stored text can tell
 * that something was removed and what kind of thing it was, which is what makes the masking
 * claim in {@code SECURITY.md} auditable rather than a promise.
 */
public enum MaskType {

	/** A person's name: patient, physician, whoever signed the report. */
	NAME("[NAME]"),

	/**
	 * A date of birth, and only one found next to a birth-date label.
	 *
	 * <p>Dates with no such label are deliberately left alone, because the specimen
	 * collection date is the one piece of date information the pipeline needs.
	 */
	DOB("[DOB]"),

	/** A record number, accession number, or similar identifier. */
	ID("[ID]"),

	PHONE("[PHONE]"),

	EMAIL("[EMAIL]"),

	/** A US social security number. */
	SSN("[SSN]");

	private final String placeholder;

	MaskType(String placeholder) {
		this.placeholder = placeholder;
	}

	public String placeholder() {
		return placeholder;
	}
}
