package dev.saq.mediscan.report;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One validated lab value.
 *
 * <p>Everything numeric here was parsed in code from the model's printed strings, and
 * {@link #flag} was computed in code. Nothing on this row was taken from the model as a
 * number ({@code CLAUDE.md} rule 4). {@link #rawValue} was verified to appear in the masked
 * text as a whole token before the row was kept.
 */
@Entity
@Table(name = "biomarker")
public class BiomarkerEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "report_id", nullable = false, updatable = false)
	private UUID reportId;

	/** The order the value had in the report. Results are always read back by this. */
	@Column(name = "position", nullable = false)
	private int position;

	@Column(name = "test_name", nullable = false)
	private String testName;

	@Column(name = "test_name_norm", nullable = false)
	private String testNameNorm;

	@Column(name = "biomarker_slug")
	private String biomarkerSlug;

	@Column(name = "raw_value", nullable = false)
	private String rawValue;

	/** Null unless the value is a plain number - a comparator value is not a trend point. */
	@Column(name = "numeric_value")
	private Double numericValue;

	@Column(name = "unit")
	private String unit;

	@Column(name = "reference_range_text")
	private String referenceRangeText;

	@Column(name = "ref_low")
	private Double refLow;

	@Column(name = "ref_high")
	private Double refHigh;

	@Enumerated(EnumType.STRING)
	@Column(name = "flag", nullable = false)
	private Flag flag;

	protected BiomarkerEntity() {
		// for JPA
	}

	public BiomarkerEntity(UUID id, UUID reportId, int position, String testName, String testNameNorm,
			String biomarkerSlug, String rawValue, Double numericValue, String unit,
			String referenceRangeText, Double refLow, Double refHigh, Flag flag) {
		this.id = id;
		this.reportId = reportId;
		this.position = position;
		this.testName = testName;
		this.testNameNorm = testNameNorm;
		this.biomarkerSlug = biomarkerSlug;
		this.rawValue = rawValue;
		this.numericValue = numericValue;
		this.unit = unit;
		this.referenceRangeText = referenceRangeText;
		this.refLow = refLow;
		this.refHigh = refHigh;
		this.flag = flag;
	}

	public UUID getId() {
		return id;
	}

	public UUID getReportId() {
		return reportId;
	}

	public int getPosition() {
		return position;
	}

	public String getTestName() {
		return testName;
	}

	public String getTestNameNorm() {
		return testNameNorm;
	}

	public String getBiomarkerSlug() {
		return biomarkerSlug;
	}

	public String getRawValue() {
		return rawValue;
	}

	public Double getNumericValue() {
		return numericValue;
	}

	public String getUnit() {
		return unit;
	}

	public String getReferenceRangeText() {
		return referenceRangeText;
	}

	public Double getRefLow() {
		return refLow;
	}

	public Double getRefHigh() {
		return refHigh;
	}

	public Flag getFlag() {
		return flag;
	}

	/**
	 * Hides the test name, the value, the unit and the range. A biomarker row is report
	 * content: it is the result itself ({@code backend/CLAUDE.md} forbids logging biomarker
	 * values). Position and flag are safe, and are the two fields worth having when reading a
	 * log line.
	 */
	@Override
	public String toString() {
		return "BiomarkerEntity[id=" + id + ", reportId=" + reportId + ", position=" + position
				+ ", flag=" + flag + "]";
	}
}
