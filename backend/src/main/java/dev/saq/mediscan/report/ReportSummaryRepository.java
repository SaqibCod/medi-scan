package dev.saq.mediscan.report;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportSummaryRepository extends JpaRepository<ReportSummaryEntity, UUID> {

	/**
	 * One report's summary, scoped by owner for the same reason as
	 * {@link BiomarkerRepository#findOwnedBySession}.
	 *
	 * <p>Empty is a legitimate result for a report that is not {@code DONE}, so callers treat
	 * it as "no summary yet" rather than an error.
	 */
	@Query("""
			select s from ReportSummaryEntity s
			where s.reportId = :reportId
			  and exists (
			      select 1 from ReportEntity r
			      where r.id = :reportId and r.sessionId = :sessionId and r.expiresAt > :now
			  )
			""")
	Optional<ReportSummaryEntity> findOwnedBySession(@Param("reportId") UUID reportId,
			@Param("sessionId") UUID sessionId,
			@Param("now") Instant now);
}
