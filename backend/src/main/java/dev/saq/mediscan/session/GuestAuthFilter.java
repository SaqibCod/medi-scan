package dev.saq.mediscan.session;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates guest callers from the {@code X-Session-Token} header.
 *
 * <p>Follows the decision tree in {@code docs/dataflow.md} section 3.6:
 *
 * <ul>
 * <li>An {@code Authorization} header present - leave the context alone. A bearer token is
 * the stronger credential and wins, and validating it is the resource server's job
 * (phase 6).</li>
 * <li>No {@code Authorization}, a valid {@code X-Session-Token} - authenticate as
 * {@link GuestPrincipal} with {@code ROLE_GUEST}.</li>
 * <li>No {@code Authorization}, a missing, unknown or expired session token - leave the
 * request anonymous. {@code ProblemAuthenticationEntryPoint} turns that into
 * {@code 401 SESSION_INVALID} if the route needs a credential.</li>
 * </ul>
 *
 * <p>The filter never rejects a request itself. Deciding which routes need a credential
 * belongs to the filter chain's authorization rules, in one place, not spread across filters.
 *
 * <p>Deliberately not a {@code @Component}: Spring Boot auto-registers any {@code Filter}
 * bean against every request, which would run this twice. {@code SecurityConfig} constructs
 * it and places it in the chain instead.
 */
public class GuestAuthFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Session-Token";

	/** Guests get this authority; it is not a user {@code role} in the sign-in sense. */
	private static final String GUEST_AUTHORITY = "ROLE_GUEST";

	private final SessionService sessionService;

	public GuestAuthFilter(SessionService sessionService) {
		this.sessionService = sessionService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		if (shouldAuthenticateAsGuest(request)) {
			String token = request.getHeader(HEADER);
			Optional<Session> session = sessionService.findActive(token);

			// An unknown or expired token authenticates nothing. It must not fall back to
			// anything else, and it must not be logged - it is a credential.
			session.ifPresent(this::authenticate);
		}

		chain.doFilter(request, response);
	}

	/**
	 * Implements rule 16 of {@code CLAUDE.md}: a request carrying {@code Authorization} never
	 * falls back to the guest token, even when both headers are sent.
	 *
	 * <p>Without this, someone holding an expired access token plus any valid guest token
	 * would silently keep working as a guest, and would see an empty report list instead of
	 * the 401 that tells the client to refresh. In phase 1 there is no bearer support at all,
	 * so a request with an {@code Authorization} header stays anonymous and is rejected -
	 * which is the safe direction. Phase 6's resource server replaces that with the proper
	 * {@code TOKEN_EXPIRED} / {@code TOKEN_INVALID} distinction.
	 */
	private boolean shouldAuthenticateAsGuest(HttpServletRequest request) {
		boolean hasBearer = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
		boolean alreadyAuthenticated = SecurityContextHolder.getContext().getAuthentication() != null;
		return !hasBearer && !alreadyAuthenticated;
	}

	private void authenticate(Session session) {
		GuestPrincipal principal = new GuestPrincipal(session.getId());

		// Credentials are null: the token has already done its job, and holding it in the
		// security context would put it within reach of anything that logs the context.
		UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
				principal, null, List.of(new SimpleGrantedAuthority(GUEST_AUTHORITY)));

		SecurityContextHolder.getContext().setAuthentication(authentication);
	}
}
