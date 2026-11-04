package dev.saq.mediscan.report;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The masked text of a finished report.
 *
 * <p>Only ever masked text ({@code CLAUDE.md} rule 1), and only ever present for a report
 * that reached {@code DONE}: the final transaction writes this row together with the
 * biomarkers and the summary, so a failed report leaves no text behind at all.
 *
 * <p>Phase 2 writes this row and never reads it back. It exists for chat in phase 5, which
 * grounds answers in the report. {@code masked_text} has no getter for that reason - add one
 * when something legitimately needs it, so the blast radius stays visible in the diff.
 */
@Entity
@Table(name = "report_text")
public class ReportTextEntity {

	@Id
	@Column(name = "report_id", nullable = false, updatable = false)
	private UUID reportId;

	@Column(name = "masked_text", nullable = false)
	private String maskedText;

	protected ReportTextEntity() {
		// for JPA
	}

	public ReportTextEntity(UUID reportId, String maskedText) {
		this.reportId = reportId;
		this.maskedText = maskedText;
	}

	public UUID getReportId() {
		return reportId;
	}

	/**
	 * The masked report text.
	 *
	 * <p>There was deliberately no accessor for this until Phase 5: nothing needed to read the
	 * text back, and not offering it meant it could not be read casually. Chat is the first
	 * legitimate reader - it is the only feature that puts the report itself in a prompt,
	 * because the summary step receives the validated values alone ({@code CLAUDE.md} rule 5).
	 *
	 * <p>Masked, never raw: the raw text existed only in memory inside the job and was gone
	 * before this row was written. It is still report content, so it must not be logged, and
	 * {@link #toString()} continues to hide it.
	 */
	public String getMaskedText() {
		return maskedText;
	}

	/**
	 * Hides the text. A record or entity's {@code toString} is exactly how report content
	 * ends up in a log line or a debugger transcript, and masking is best-effort - the text
	 * is not safe to print even after it ran ({@code backend/CLAUDE.md}, "Privacy in code").
	 */
	@Override
	public String toString() {
		return "ReportTextEntity[reportId=" + reportId + ", length="
				+ (maskedText != null ? maskedText.length() : 0) + "]";
	}
}
