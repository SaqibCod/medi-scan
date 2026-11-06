package dev.saq.mediscan.report;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Owner-scoped reads of a report's masked text.
 *
 * <p>Written since Phase 2 and never read back until now: chat is the first thing that needs
 * the report itself in a prompt, because the summary step deliberately receives only the
 * validated values ({@code CLAUDE.md} rule 5).
 *
 * <p>Both methods take an owner and an expiry, like every other report query
 * ({@code CLAUDE.md} rule 7). There is no unscoped {@code findById} here on purpose - the text
 * is the whole report, so a method that could fetch it without an owner would be the single
 * most damaging shortcut in the codebase.
 */
public interface ReportTextRepository extends JpaRepository<ReportTextEntity, UUID> {

	/** The masked text, if this report belongs to this guest session and has not expired. */
	@Query("""
			select t from ReportTextEntity t, ReportEntity r
			where t.reportId = :id and r.id = :id
			  and r.sessionId = :sessionId and r.expiresAt > :now
			""")
	Optional<ReportTextEntity> findOwnedBySession(@Param("id") UUID id,
			@Param("sessionId") UUID sessionId,
			@Param("now") Instant now);

	/** The user-owned twin, filtering on the column its own index covers. */
	@Query("""
			select t from ReportTextEntity t, ReportEntity r
			where t.reportId = :id and r.id = :id
			  and r.userId = :userId and r.expiresAt > :now
			""")
	Optional<ReportTextEntity> findOwnedByUser(@Param("id") UUID id,
			@Param("userId") UUID userId,
			@Param("now") Instant now);
}
