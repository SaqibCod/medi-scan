package dev.saq.mediscan.report;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Report persistence.
 *
 * <p>Every method that reads or deletes a caller's report takes both the report id and the
 * owner id, and filters on expiry. There is deliberately no {@code findById} in use: the one
 * inherited from {@link JpaRepository} would let a caller reach another owner's report, so
 * callers go through {@link ReportService}, which only ever hands over an
 * {@link dev.saq.mediscan.session.OwnerRef}-scoped query ({@code CLAUDE.md} rule 7).
 *
 * <p>The expiry filter lives in the query rather than the caller because
 * {@code docs/dataflow.md} section 9.2 requires reads to exclude expired data even before
 * the retention job has removed it.
 */
public interface ReportRepository extends JpaRepository<ReportEntity, UUID> {

	/**
	 * One report, if it belongs to this guest session and has not expired.
	 *
	 * <p>An empty result is what becomes {@code 404 REPORT_NOT_FOUND}, for a non-existent id
	 * and for someone else's id alike. The API never distinguishes the two, so it never
	 * confirms that an id exists.
	 */
	@Query("""
			select r from ReportEntity r
			where r.id = :id and r.sessionId = :sessionId and r.expiresAt > :now
			""")
	Optional<ReportEntity> findOwnedBySession(@Param("id") UUID id,
			@Param("sessionId") UUID sessionId,
			@Param("now") Instant now);

	/**
	 * Deletes one report if it belongs to this guest session, cascading to its text, values
	 * and summary.
	 *
	 * <p>No expiry filter: deleting an expired report is harmless and still returns the
	 * {@code 204} the caller expects, where filtering would turn it into a confusing 404.
	 *
	 * @return 1 when a row was removed, 0 when there was nothing to remove
	 */
	@Modifying
	@Query("delete from ReportEntity r where r.id = :id and r.sessionId = :sessionId")
	int deleteOwnedBySession(@Param("id") UUID id, @Param("sessionId") UUID sessionId);

	/**
	 * Expired reports, removed as a safety net.
	 *
	 * <p>The session cascade already takes a guest's reports when the session expires. This
	 * exists because {@code report.expires_at} is its own column, and phase 6 gives user
	 * reports a 30-day expiry that no session cascade covers.
	 */
	@Modifying
	@Query("delete from ReportEntity r where r.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);
}
