package medi_scan.backend.session;

import java.util.UUID;

/**
 * Who owns a report: exactly one of a signed-in user or a guest session.
 *
 * <p>This type exists to make rule 7 of {@code CLAUDE.md} structural rather than a habit.
 * Every report repository method takes an {@code OwnerRef}, so a report query cannot be
 * written without an owner filter - the compiler asks for one. The matching SQL is:
 *
 * <ul>
 * <li>{@link User} - {@code WHERE id = ? AND user_id = ? AND expires_at > now()}</li>
 * <li>{@link Guest} - {@code WHERE id = ? AND session_id = ? AND expires_at > now()}</li>
 * </ul>
 *
 * <p>Sealed so adding a third kind of owner has to be a deliberate change that updates every
 * exhaustive switch over it, instead of silently falling through to a default.
 *
 * <p>Phase 1 only ever constructs {@link Guest}; {@link User} arrives with sign-in in phase 6.
 * It is defined now so phase 2's report queries are written against the final shape.
 */
public sealed interface OwnerRef {

	/** The owner's primary key, in whichever table owns it. */
	UUID id();

	/** A signed-in user, identified by {@code app_user.id}. */
	record User(UUID id) implements OwnerRef {
	}

	/** A guest, identified by {@code session.id}. */
	record Guest(UUID id) implements OwnerRef {
	}
}
