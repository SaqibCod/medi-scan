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

	/** Google's `name` claim is free text; 100 characters is more than a header can show. */
	static final int MAX_DISPLAY_NAME = 100;

	/** What to store when Google sent no usable name. Never blank, because the UI shows it. */
	static final String FALLBACK_DISPLAY_NAME = "User";

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
		return upsert.upsert(googleSub, normalizeDisplayName(displayName), role,
				Instant.now(clock));
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

	/**
	 * Trims, caps at {@value #MAX_DISPLAY_NAME} characters, and falls back to
	 * {@value #FALLBACK_DISPLAY_NAME}.
	 *
	 * <p>Google's {@code name} claim is user-controlled, so it is treated as untrusted input:
	 * capped so it cannot bloat a row, and never interpolated anywhere but a JSON response the
	 * client renders as text.
	 */
	static String normalizeDisplayName(String raw) {
		if (raw == null || raw.isBlank()) {
			return FALLBACK_DISPLAY_NAME;
		}
		String trimmed = raw.trim();
		return trimmed.length() > MAX_DISPLAY_NAME
				? trimmed.substring(0, MAX_DISPLAY_NAME)
				: trimmed;
	}
}
