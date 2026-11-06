package dev.saq.mediscan.trends;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.SqlTime;
import dev.saq.mediscan.report.Flag;

/**
 * Read-only queries behind {@code GET /api/trends} and {@code GET /api/trends/markers}
 * (contract section 5, LLD 6).
 *
 * <p>Every query is scoped to {@code user_id} directly - trends are signed-in only, so there is
 * no guest column to switch on, unlike {@code report.ReportListQuery}.
 */
@Component
public class TrendsQuery {

	private static final String MARKERS = """
			WITH matched AS (
			    SELECT b.report_id, b.biomarker_slug, b.test_name, r.created_at,
			           coalesce(b.biomarker_slug, b.test_name_norm) AS marker_key
			      FROM biomarker b
			      JOIN report r ON r.id = b.report_id
			     WHERE r.user_id = :userId
			       AND r.status = 'DONE'
			       AND r.expires_at > :now
			       AND b.numeric_value IS NOT NULL
			),
			counts AS (
			    SELECT marker_key, count(DISTINCT report_id) AS report_count
			      FROM matched
			     GROUP BY marker_key
			    HAVING count(DISTINCT report_id) >= :minReports
			),
			latest AS (
			    SELECT marker_key, biomarker_slug, test_name,
			           row_number() OVER (PARTITION BY marker_key ORDER BY created_at DESC) AS rn
			      FROM matched
			)
			SELECT c.marker_key, l.biomarker_slug, l.test_name, c.report_count
			  FROM counts c
			  JOIN latest l ON l.marker_key = c.marker_key AND l.rn = 1
			""";

	private static final String POINTS = """
			SELECT b.report_id, r.collected_on, r.created_at, b.position, b.unit,
			       b.numeric_value, b.ref_low, b.ref_high, b.flag, b.biomarker_slug, b.test_name
			  FROM biomarker b
			  JOIN report r ON r.id = b.report_id
			 WHERE r.user_id = :userId
			   AND r.status = 'DONE'
			   AND r.expires_at > :now
			   AND b.numeric_value IS NOT NULL
			   AND (b.biomarker_slug = :marker OR b.test_name_norm = :normalizedMarker)
			 ORDER BY coalesce(r.collected_on, (r.created_at AT TIME ZONE 'UTC')::date) ASC,
			          r.created_at ASC,
			          b.position ASC
			""";

	private final JdbcClient jdbc;

	public TrendsQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Markers present in at least {@code minReports} of the user's {@code DONE} reports. */
	public List<MarkerRow> markers(UUID userId, Instant now, int minReports) {
		return jdbc.sql(MARKERS)
				.param("userId", userId)
				.param("now", SqlTime.utc(now))
				.param("minReports", minReports)
				.query((rs, rowNum) -> new MarkerRow(
						rs.getString("marker_key"),
						rs.getString("biomarker_slug"),
						rs.getString("test_name"),
						rs.getLong("report_count")))
				.list();
	}

	/**
	 * Every matching, numeric row for {@code marker} across the user's {@code DONE} reports.
	 * Repeated tests in one report come back as separate rows - nothing here merges them.
	 */
	public List<PointRow> points(UUID userId, String marker, String normalizedMarker, Instant now) {
		return jdbc.sql(POINTS)
				.param("userId", userId)
				.param("marker", marker)
				.param("normalizedMarker", normalizedMarker)
				.param("now", SqlTime.utc(now))
				.query(TrendsQuery::toPointRow)
				.list();
	}

	private static PointRow toPointRow(ResultSet rs, int rowNum) throws SQLException {
		java.sql.Date collectedOn = rs.getDate("collected_on");
		return new PointRow(
				rs.getObject("report_id", UUID.class),
				collectedOn == null ? null : collectedOn.toLocalDate(),
				rs.getTimestamp("created_at").toInstant(),
				rs.getInt("position"),
				rs.getString("unit"),
				rs.getDouble("numeric_value"),
				(Double) rs.getObject("ref_low"),
				(Double) rs.getObject("ref_high"),
				Flag.valueOf(rs.getString("flag")),
				rs.getString("biomarker_slug"),
				rs.getString("test_name"));
	}

	/** One group from {@link #markers}, before display-name resolution. */
	public record MarkerRow(String markerKey, String biomarkerSlug, String testName, long reportCount) {

		@Override
		public String toString() {
			return "MarkerRow[markerKey=" + markerKey + ", reportCount=" + reportCount + "]";
		}
	}

	/** One row from {@link #points}, before grouping and the per-series cap. */
	public record PointRow(UUID reportId, LocalDate collectedOn, Instant createdAt, int position,
			String unit, double numericValue, Double refLow, Double refHigh, Flag flag,
			String biomarkerSlug, String testName) {

		@Override
		public String toString() {
			return "PointRow[reportId=" + reportId + "]";
		}
	}
}
