package medi_scan.backend.session;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, UUID> {

	/**
	 * Looks a session up by token hash, rejecting expired ones in the query itself.
	 *
	 * <p>The expiry check belongs here rather than in the caller: {@code docs/dataflow.md}
	 * section 9.2 requires reads to filter on {@code expires_at > now()} so expired data is
	 * never served in the window before the cleanup job runs.
	 */
	@Query("select s from Session s where s.tokenHash = :tokenHash and s.expiresAt > :now")
	Optional<Session> findActiveByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

	/**
	 * Deletes sessions that have expired, cascading to the reports they own.
	 *
	 * <p>{@code @Modifying} with a bulk delete rather than {@code findAll} then
	 * {@code deleteAll}: the job must not load every expired row into memory on a 2 GB box,
	 * and the database-level {@code ON DELETE CASCADE} does the rest.
	 *
	 * @return how many sessions were removed
	 */
	@Modifying
	@Query("delete from Session s where s.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);
}
