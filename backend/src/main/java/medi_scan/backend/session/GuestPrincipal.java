package medi_scan.backend.session;

import java.util.UUID;

/**
 * The authenticated principal for a guest caller.
 *
 * <p>Carries the session id and nothing else - no token, so the credential cannot be reached
 * from the security context and cannot end up in a log line.
 *
 * @param sessionId {@code session.id} of the session the token resolved to
 */
public record GuestPrincipal(UUID sessionId) {

	/** The owner reference report queries filter by. */
	public OwnerRef toOwnerRef() {
		return new OwnerRef.Guest(sessionId);
	}
}
