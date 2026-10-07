package dev.saq.mediscan.report;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a finished report: text, values, summary and {@code DONE}, all at once.
 *
 * <p>One transaction, for two reasons. A report is never left with values but no summary, so
 * the results page has nothing partial to render. And the masked text is written here rather
 * than when masking finished, which means <strong>a report that failed stores no text at
 * all</strong> ({@code docs/dataflow.md} section 4.2) - the least data this design can keep.
 *
 * <p>The status update comes first and is conditional on {@code PROCESSING}. If the caller
 * deleted the report mid-job it matches nothing, and the whole transaction rolls back rather
 * than inserting rows that belong to an id the caller already removed.
 */
@Service
public class ReportResultSaver {

	private static final Logger log = LoggerFactory.getLogger(ReportResultSaver.class);

	private final JdbcClient jdbc;
	private final ReportStatusService statuses;
	private final JsonMapper jsonMapper;

	public ReportResultSaver(JdbcClient jdbc, ReportStatusService statuses, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.statuses = statuses;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Saves everything and marks the report done.
	 *
	 * @return {@code true} when the report was saved; {@code false} when it had been deleted,
	 *     in which case nothing was written and the job should simply stop
	 */
	@Transactional
	public boolean saveDone(UUID reportId, String maskedText, List<BiomarkerRow> biomarkers,
			String patientSummary, List<String> highlights, LocalDate collectedOn) {

		// Conditional on PROCESSING. A deleted report fails here, before any child row is
		// inserted (LLD 7.2).
		if (!statuses.markDone(reportId, collectedOn)) {
			log.info("report vanished before save id={}", reportId);
			return false;
		}

		try {
			insertText(reportId, maskedText);
			insertBiomarkers(reportId, biomarkers);
			insertSummary(reportId, patientSummary, highlights);
			return true;
		}
		catch (DataIntegrityViolationException ex) {
			// The report was deleted between the status update and these inserts, so the
			// foreign key no longer resolves. Rolling back is the whole response: the caller
			// asked for it to be gone.
			log.info("report deleted during save id={}", reportId);
			throw new ReportVanishedException();
		}
	}

	private void insertText(UUID reportId, String maskedText) {
		jdbc.sql("insert into report_text (report_id, masked_text) values (:id, :text)")
				.param("id", reportId)
				.param("text", maskedText)
				.update();
	}

	/**
	 * Inserts the values in report order.
	 *
	 * <p>A batch, because a panel can be thirty rows and thirty round trips inside a
	 * transaction is thirty times the lock duration for no reason.
	 */
	private void insertBiomarkers(UUID reportId, List<BiomarkerRow> biomarkers) {
		if (biomarkers.isEmpty()) {
			return;
		}

		String sql = """
				insert into biomarker (
				    id, report_id, position, test_name, test_name_norm, biomarker_slug,
				    raw_value, numeric_value, unit, reference_range_text, ref_low, ref_high, flag)
				values (
				    :id, :reportId, :position, :testName, :testNameNorm, :slug,
				    :rawValue, :numericValue, :unit, :referenceRangeText, :refLow, :refHigh, :flag)
				""";

		for (BiomarkerRow row : biomarkers) {
			jdbc.sql(sql)
					.param("id", UUID.randomUUID())
					.param("reportId", reportId)
					.param("position", row.position())
					.param("testName", row.testName())
					.param("testNameNorm", row.testNameNorm())
					.param("slug", row.biomarkerSlug())
					.param("rawValue", row.rawValue())
					.param("numericValue", row.numericValue())
					.param("unit", row.unit())
					.param("referenceRangeText", row.referenceRangeText())
					.param("refLow", row.refLow())
					.param("refHigh", row.refHigh())
					.param("flag", row.flag().name())
					.update();
		}
	}

	private void insertSummary(UUID reportId, String patientSummary, List<String> highlights) {
		jdbc.sql("""
				insert into report_summary (report_id, patient_summary, highlights_json)
				values (:id, :summary, :highlights::jsonb)
				""")
				.param("id", reportId)
				.param("summary", patientSummary)
				.param("highlights", jsonMapper.writeValueAsString(highlights))
				.update();
	}

	/**
	 * One row to insert.
	 *
	 * <p>Declared here rather than reusing {@code analysis}'s validated type, so {@code report}
	 * does not depend on {@code analysis} - the dependency runs the other way
	 * ({@code backend/CLAUDE.md}, "Dependency direction").
	 */
	public record BiomarkerRow(
			int position,
			String testName,
			String testNameNorm,
			String biomarkerSlug,
			String rawValue,
			Double numericValue,
			String unit,
			String referenceRangeText,
			Double refLow,
			Double refHigh,
			Flag flag) {

		/** Hides the name, value, unit and range: a row is the result itself. */
		@Override
		public String toString() {
			return "BiomarkerRow[position=" + position + ", flag=" + flag + "]";
		}
	}

	/**
	 * The report was deleted while it was being saved.
	 *
	 * <p>Rolls the transaction back without being an error: the caller asked for the report to
	 * be gone, and it is.
	 */
	public static class ReportVanishedException extends RuntimeException {

		public ReportVanishedException() {
			super("report was deleted during save", null, false, false);
		}
	}
}
