package dev.saq.mediscan.user;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * User reads and deletes.
 *
 * <p>The sign-in write is not here: it has to be a single {@code INSERT ... ON CONFLICT ...
 * RETURNING} so that two parallel first sign-ins cannot both create a row, and Spring Data
 * cannot express that. See {@link UserUpsert}.
 *
 * <p>{@code existsById} is inherited and is deliberately used on every bearer request
 * (LLD 6.5). It is a primary-key lookup, and it is what makes account deletion take effect
 * immediately instead of after the access token expires.
 */
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

	/**
	 * Deletes accounts that have not signed in for a long time.
	 *
	 * <p>The cascade from {@code app_user} takes their reports, report text, biomarkers,
	 * summaries and refresh tokens with them, so this one statement is the whole cleanup
	 * (docs/dataflow.md section 9.2).
	 *
	 * @return how many accounts were removed
	 */
	@Modifying
	@Query("delete from UserEntity u where u.lastLoginAt < :cutoff")
	int deleteInactiveSince(@Param("cutoff") Instant cutoff);

	/**
	 * Writes a recomputed role.
	 *
	 * <p>A targeted update rather than loading, mutating and flushing the entity: the role is
	 * the only field a refresh may change, and this makes that explicit.
	 */
	@Modifying
	@Query("update UserEntity u set u.role = :role where u.id = :id")
	int updateRole(@Param("id") UUID id, @Param("role") UserRole role);
}
