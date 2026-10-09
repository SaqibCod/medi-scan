package dev.saq.mediscan.report;

/**
 * Where a report's text came from. Reported back as {@code sourceType} (contract section 4.1).
 *
 * <p>{@link #IMAGE} exists in the enum and in the table's check constraint but is never
 * written in phase 2: image uploads are rejected at the door with
 * {@code 415 UNSUPPORTED_FILE_TYPE} until the OCR pipeline arrives in phase 4 (contract
 * section 4.1). Declaring it now means phase 4 adds an extractor rather than a migration.
 */
public enum SourceType {

	/** An uploaded PDF with a usable text layer. */
	PDF,

	/** An uploaded PNG or JPEG. Not accepted until phase 4. */
	IMAGE,

	/** Text pasted into the client. */
	TEXT,

	/** One of the bundled synthetic sample reports. */
	SAMPLE
}
