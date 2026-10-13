package dev.saq.mediscan.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.SqlTime;
import dev.saq.mediscan.session.OwnerRef;

/**
 * Read-only queries behind {@code GET /api/reports} (contract section 4.5, LLD 5).
 *
 * <p>One SQL template per owner column, selected in code by the sealed {@link OwnerRef} -
 * never built from a caller-supplied value ({@code backend/CLAUDE.md}, "SQL"). The
 * {@code (:status::text IS NULL OR status = :status)} shape is the standard way to make one
 * query serve both "any status" and "exactly this status" without building SQL per request.
 */
@Component
public class ReportListQuery {

	private static final String SESSION_PAGE = """
			SELECT id, status, source_type, created_at, expires_at, collected_on, error_code
			  FROM report
			 WHERE session_id = :ownerId
			   AND expires_at > :now
			   AND (:status::text IS NULL OR status = :status)
			 ORDER BY created_at DESC, id DESC
			 LIMIT :limit OFFSET :offset
			""";

	private static final String USER_PAGE = """
			SELECT id, status, source_type, created_at, expires_at, collected_on, error_code
			  FROM report
			 WHERE user_id = :ownerId
			   AND expires_at > :now
			   AND (:status::text IS NULL OR status = :status)
			 ORDER BY created_at DESC, id DESC
			 LIMIT :limit OFFSET :offset
			""";

	private static final String SESSION_COUNT = """
			SELECT count(*) FROM report
			 WHERE session_id = :ownerId
			   AND expires_at > :now
			   AND (:status::text IS NULL OR status = :status)
			""";

	private static final String USER_COUNT = """
			SELECT count(*) FROM report
			 WHERE user_id = :ownerId
			   AND expires_at > :now
			   AND (:status::text IS NULL OR status = :status)
			""";

	private final JdbcClient jdbc;

	public ReportListQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** One page, newest first, with a stable order when two reports share a timestamp. */
	public List<Row> page(OwnerRef owner, ReportStatus status, Instant now, int limit, int offset) {
		String sql = switch (owner) {
			case OwnerRef.Guest guest -> SESSION_PAGE;
			case OwnerRef.User user -> USER_PAGE;
		};
		return jdbc.sql(sql)
				.param("ownerId", owner.id())
				.param("status", status == null ? null : status.name())
				.param("now", SqlTime.utc(now))
				.param("limit", limit)
				.param("offset", offset)
				.query(ReportListQuery::toRow)
				.list();
	}

	/** How many reports match, for {@code totalItems}. */
	public long count(OwnerRef owner, ReportStatus status, Instant now) {
		String sql = switch (owner) {
			case OwnerRef.Guest guest -> SESSION_COUNT;
			case OwnerRef.User user -> USER_COUNT;
		};
		Long total = jdbc.sql(sql)
				.param("ownerId", owner.id())
				.param("status", status == null ? null : status.name())
				.param("now", SqlTime.utc(now))
				.query(Long.class)
				.single();
		return total != null ? total : 0;
	}

	/**
	 * Flag counts for a set of {@code DONE} reports, one grouped query rather than one per row
	 * (LLD 5, the query-count test).
	 */
	public Map<UUID, ReportResponse.Counts> countsFor(List<UUID> reportIds) {
		if (reportIds.isEmpty()) {
			return Map.of();
		}

		List<FlagCount> rows = jdbc.sql("""
				SELECT report_id, flag, count(*) AS cnt
				  FROM biomarker
				 WHERE report_id IN (:ids)
				 GROUP BY report_id, flag
				""")
				.param("ids", reportIds)
				.query((rs, rowNum) -> new FlagCount(
						rs.getObject("report_id", UUID.class),
						Flag.valueOf(rs.getString("flag")),
						rs.getInt("cnt")))
				.list();

		Map<UUID, int[]> tallies = new HashMap<>();
		for (FlagCount row : rows) {
			int[] tally = tallies.computeIfAbsent(row.reportId(), id -> new int[4]);
			switch (row.flag()) {
				case LOW -> tally[0] += row.count();
				case NORMAL -> tally[1] += row.count();
				case HIGH -> tally[2] += row.count();
				case UNKNOWN -> tally[3] += row.count();
			}
		}

		Map<UUID, ReportResponse.Counts> result = new HashMap<>();
		tallies.forEach((id, t) -> result.put(id,
				new ReportResponse.Counts(t[0] + t[1] + t[2] + t[3], t[0], t[1], t[2], t[3])));
		return result;
	}

	private static Row toRow(ResultSet rs, int rowNum) throws SQLException {
		String errorCode = rs.getString("error_code");
		java.sql.Date collectedOn = rs.getDate("collected_on");
		return new Row(
				rs.getObject("id", UUID.class),
				ReportStatus.valueOf(rs.getString("status")),
				SourceType.valueOf(rs.getString("source_type")),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("expires_at").toInstant(),
				collectedOn == null ? null : collectedOn.toLocalDate(),
				errorCode == null ? null : ReportErrorCode.valueOf(errorCode));
	}

	/** One tallied flag, for {@link #countsFor}. */
	private record FlagCount(UUID reportId, Flag flag, int count) {
	}

	/** One row of the page, before mapping to the contract shape. */
	public record Row(UUID id, ReportStatus status, SourceType sourceType, Instant createdAt,
			Instant expiresAt, LocalDate collectedOn, ReportErrorCode errorCode) {

		/** Nothing here is report content, but keep the habit for every row type in this package. */
		@Override
		public String toString() {
			return "Row[id=" + id + ", status=" + status + "]";
		}
	}
}
