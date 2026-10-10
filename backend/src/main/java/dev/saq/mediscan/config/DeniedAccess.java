package dev.saq.mediscan.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import dev.saq.mediscan.session.GuestAuthFilter;

/**
 * Decides what an authorization failure should say.
 *
 * <p>Shared because the same failure can arrive by two routes, and they must not disagree:
 * the filter chain's matcher reaches {@link ProblemAccessDeniedHandler}, while a
 * {@code @PreAuthorize} on a path the matcher allowed throws inside the controller invocation
 * and reaches {@link ApiExceptionHandler}. One answer for both.
 *
 * <ul>
 * <li><strong>Guest</strong> - {@code 401 AUTH_REQUIRED}. A guest is authenticated, so Spring
 * treats this as an authorization failure, but the useful answer is "sign in": they have no
 * account, and 403 would suggest their credential was rejected.</li>
 * <li><strong>Signed-in user</strong> - {@code 403 FORBIDDEN}. They are who they say they are
 * and still may not do this.</li>
 * </ul>
 */
final class DeniedAccess {

	private DeniedAccess() {
	}

	/** {@code AUTH_REQUIRED} for a guest or an unauthenticated caller, {@code FORBIDDEN} otherwise. */
	static ErrorCode codeForCurrentCaller() {
		return isGuest() ? ErrorCode.AUTH_REQUIRED : ErrorCode.FORBIDDEN;
	}

	static String detailFor(ErrorCode code) {
		return code == ErrorCode.AUTH_REQUIRED
				? "Sign in to use this endpoint."
				: "You do not have permission to use this endpoint.";
	}

	/**
	 * Keyed on the authority {@code GuestAuthFilter} grants rather than on the principal type,
	 * so this stays correct if the guest principal ever changes shape.
	 */
	private static boolean isGuest() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			// No authentication at all normally reaches the entry point, not here. Treat it
			// as a guest: "sign in" is the useful answer either way.
			return true;
		}
		for (GrantedAuthority authority : authentication.getAuthorities()) {
			if (GuestAuthFilter.GUEST_AUTHORITY.equals(authority.getAuthority())) {
				return true;
			}
		}
		return false;
	}
}
