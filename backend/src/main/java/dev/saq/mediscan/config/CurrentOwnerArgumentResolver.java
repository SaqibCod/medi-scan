package dev.saq.mediscan.config;

import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import dev.saq.mediscan.session.GuestPrincipal;
import dev.saq.mediscan.session.OwnerRef;
import dev.saq.mediscan.user.UserService;

/**
 * Turns the security principal into an {@link OwnerRef}.
 *
 * <p>The single translation point between "who is authenticated" and "whose data is this".
 * Report code never reads the security context, so an owner filter cannot be forgotten - the
 * compiler asks for an {@code OwnerRef} and this is the only thing that produces one
 * ({@code CLAUDE.md} rule 7).
 *
 * <p><strong>The user-existence check.</strong> A bearer principal costs one primary-key
 * lookup on {@code app_user} before it becomes an owner. That is what makes account deletion
 * take effect immediately rather than whenever the access token happens to expire: the token
 * still passes every signature and claim check for up to fifteen minutes, but there is no
 * longer an account behind it, so the request fails with {@code 401 AUTH_REQUIRED}
 * (docs/dataflow.md section 8.1, LLD 6.5).
 *
 * <p>It also prevents a worse failure. Without it, an upload from a deleted account would
 * reach {@code INSERT INTO report ... user_id = ?} and die on a foreign key, which is a 500
 * for something that is really an authentication problem.
 *
 * <p>Guests need no equivalent check: {@code GuestAuthFilter} only authenticates a session it
 * has just loaded from the database, so the row is known to exist.
 */
@Component
public class CurrentOwnerArgumentResolver implements HandlerMethodArgumentResolver {

	private final UserService users;

	public CurrentOwnerArgumentResolver(UserService users) {
		this.users = users;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentOwner.class)
				&& OwnerRef.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication instanceof JwtAuthenticationToken bearer) {
			return userOwner(bearer);
		}
		if (authentication != null
				&& authentication.getPrincipal() instanceof GuestPrincipal guest) {
			return guest.toOwnerRef();
		}

		// Defensive: the filter chain requires a credential on every route that resolves an
		// owner, so this is unreachable through the API. It is here so that adding a route
		// and forgetting its authorization rule fails closed.
		throw new ApiException(ErrorCode.AUTH_REQUIRED, "Sign in to use this endpoint.");
	}

	private OwnerRef userOwner(JwtAuthenticationToken bearer) {
		// The decoder has already proved `sub` is a UUID, so this cannot throw.
		UUID userId = UUID.fromString(bearer.getToken().getSubject());

		if (!users.exists(userId)) {
			throw new ApiException(ErrorCode.AUTH_REQUIRED,
					"This account no longer exists. Sign in again.");
		}
		return new OwnerRef.User(userId);
	}
}
