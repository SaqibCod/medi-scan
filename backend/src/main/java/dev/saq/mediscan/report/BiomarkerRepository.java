package dev.saq.mediscan.report;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiomarkerRepository extends JpaRepository<BiomarkerEntity, UUID> {

	/**
	 * One report's values, in the order they were printed.
	 *
	 * <p>Scoped by owner even though the caller has already proved ownership to load the
	 * report itself. The redundancy is the point: {@code backend/CLAUDE.md} requires every
	 * repository method that reads report data to take the owner, so a future caller cannot
	 * reach this one with an id it got from somewhere else. The subquery costs an index
	 * lookup on a table with at most a few dozen rows per report.
	 */
	@Query("""
			select b from BiomarkerEntity b
			where b.reportId = :reportId
			  and exists (
			      select 1 from ReportEntity r
			      where r.id = :reportId and r.sessionId = :sessionId and r.expiresAt > :now
			  )
			order by b.position asc
			""")
	List<BiomarkerEntity> findOwnedBySession(@Param("reportId") UUID reportId,
			@Param("sessionId") UUID sessionId,
			@Param("now") Instant now);
}
