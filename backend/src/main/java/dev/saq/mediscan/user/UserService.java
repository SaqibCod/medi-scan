package dev.saq.mediscan.user;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Users: sign-in writes, the role decision, account deletion, and the existence check.
 *
 * <p>The role is never read from the database to decide anything. It is recomputed from
 * {@link AdminSubjects} on every sign-in and every refresh and written back when it changed,
 * so {@code app_user.role} is a cache of the config rather than a source of truth
 * (docs/dataflow.md section 3.2, CLAUDE.md rule 17).
 */
@Service
public class UserService {

	private static final Logger log = LoggerFactory.getLogger(UserService.class);

	private final UserRepository users;
	private final UserUpsert upsert;
	private final AdminSubjects adminSubjects;
	private final Clock clock;

	public UserService(UserRepository users, UserUpsert upsert, AdminSubjects adminSubjects,
			Clock clock) {
		this.users = users;
		this.upsert = upsert;
		this.adminSubjects = adminSubjects;
		this.clock = clock;
	}

	/**
	 * Finds or creates the user behind a verified Google identity.
	 *
	 * <p>Not transactional on its own: sign-in wraps this, the report claim and the refresh
	 * token insert in one transaction, so a failure anywhere leaves the guest exactly as they
	 * were (LLD 7.1).
	 */
	public UserUpsert.Result signIn(String googleSub, String displayName) {
		UserRole role = adminSubjects.roleFor(googleSub);
		// displayName arrives already trimmed, capped and defaulted by GoogleIdentity.of,
		// which is the single place that decides what a display name may be.
		return upsert.upsert(googleSub, displayName, role, Instant.now(clock));
	}

	/**
	 * The role this user should have right now, which may differ from the stored one when the
	 * config changed since they signed in.
	 */
	public UserRole currentRole(UserEntity user) {
		return adminSubjects.roleFor(user.getGoogleSub());
	}

	@Transactional(readOnly = true)
	public Optional<UserEntity> find(UUID userId) {
		return users.findById(userId);
	}

	/**
	 * Writes the role back when config says it should have changed.
	 *
	 * <p>Called on every refresh, so {@code app_user.role} converges on
	 * {@code ADMIN_GOOGLE_SUBS} without the user signing in again. Conditional because the
	 * common case is "unchanged", and an unconditional update would write to every row on
	 * every refresh.
	 */
	@Transactional
	public void updateRoleIfChanged(UserEntity user, UserRole role) {
		if (user.getRole() == role) {
			return;
		}
		users.updateRole(user.getId(), role);
		log.info("role changed user={} role={}", user.getId(), role);
	}

	/**
	 * Whether the account still exists.
	 *
	 * <p>Called on every bearer request while resolving the owner (LLD 6.5). One primary-key
	 * lookup, and the reason a deleted account stops working immediately rather than when its
	 * access token expires.
	 */
	@Transactional(readOnly = true)
	public boolean exists(UUID userId) {
		return users.existsById(userId);
	}

	/**
	 * Deletes the account and everything that hangs off it.
	 *
	 * <p>One statement: {@code ON DELETE CASCADE} from {@code app_user} removes the reports,
	 * and from each report its text, biomarkers and summary, plus every refresh token
	 * (docs/dataflow.md section 8.1). Jobs still running for those reports stop at their next
	 * conditional status update, because the row they would write to is gone.
	 */
	@Transactional
	public void delete(UUID userId) {
		users.deleteById(userId);
		// Id only. Never the Google sub and never the display name (LLD 12).
		log.info("account deleted user={}", userId);
	}

}
