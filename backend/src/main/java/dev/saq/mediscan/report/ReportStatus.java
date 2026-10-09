package dev.saq.mediscan.report;

/**
 * Where a report is in the pipeline ({@code docs/dataflow.md} section 4.3).
 *
 * <p>Stored as text and mirrored by a check constraint in {@code V2__reports.sql}, so a value
 * this enum does not know about cannot reach the table.
 *
 * <p>Transitions are deliberately one-way and are applied as conditional updates by
 * {@link ReportStatusService}: {@code PENDING -> PROCESSING -> DONE | FAILED}. Nothing moves
 * out of a terminal state, which is what stops a job from reviving a report the caller
 * deleted while it was running.
 */
public enum ReportStatus {

	/** Accepted and queued. No worker has picked it up yet. */
	PENDING,

	/** A worker is running the pipeline for it. */
	PROCESSING,

	/** Finished. Text, biomarkers and summary are all stored. */
	DONE,

	/** Finished unsuccessfully. {@code error_code} says why. */
	FAILED
}
