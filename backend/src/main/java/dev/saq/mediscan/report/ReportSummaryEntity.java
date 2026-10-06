package dev.saq.mediscan.report;

import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The plain-language summary, written from the validated biomarkers only.
 *
 * <p>The model that produced this text never saw the report: step 2 receives the checked
 * value list and nothing else ({@code CLAUDE.md} rule 5). It still counts as report content,
 * because it quotes values and ranges.
 */
@Entity
@Table(name = "report_summary")
public class ReportSummaryEntity {

	@Id
	@Column(name = "report_id", nullable = false, updatable = false)
	private UUID reportId;

	@Column(name = "patient_summary", nullable = false)
	private String patientSummary;

	/**
	 * One short sentence per {@code LOW} or {@code HIGH} marker, already filtered in code.
	 *
	 * <p>Mapped as JSON rather than a child table because it is read and written as a whole
	 * list and never queried into. {@code SqlTypes.JSON} becomes {@code jsonb} on PostgreSQL.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "highlights_json", nullable = false)
	private List<String> highlights;

	protected ReportSummaryEntity() {
		// for JPA
	}

	public ReportSummaryEntity(UUID reportId, String patientSummary, List<String> highlights) {
		this.reportId = reportId;
		this.patientSummary = patientSummary;
		this.highlights = highlights;
	}

	public UUID getReportId() {
		return reportId;
	}

	public String getPatientSummary() {
		return patientSummary;
	}

	public List<String> getHighlights() {
		return highlights;
	}

	/** Hides the summary text and the highlights, for the reasons in {@link ReportTextEntity}. */
	@Override
	public String toString() {
		return "ReportSummaryEntity[reportId=" + reportId + ", highlights="
				+ (highlights != null ? highlights.size() : 0) + "]";
	}
}
