package dev.saq.mediscan.user;

/**
 * What a signed-in user may do. Guests have no role at all.
 *
 * <p>{@code ADMIN} comes from {@code ADMIN_GOOGLE_SUBS} and nowhere else: no endpoint and no
 * UI grants it (CLAUDE.md rule 17). It is recomputed on every sign-in and every refresh, so
 * removing a {@code sub} from the config takes effect within one access token lifetime.
 */
public enum UserRole {

	USER,
	ADMIN;

	/** The Spring Security authority for this role, which is where the {@code ROLE_} prefix enters. */
	public String authority() {
		return "ROLE_" + name();
	}
}
