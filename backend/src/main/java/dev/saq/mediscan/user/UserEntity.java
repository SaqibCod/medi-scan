package dev.saq.mediscan.user;

import java.time.Instant;
import java.util.UUID;

import dev.saq.mediscan.session.OwnerRef;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A signed-in user.
 *
 * <p>Four columns and two timestamps, which is the whole point: Medi-Scan stores Google's
 * {@code sub}, a display name, a role, and when the account was created and last used. No
 * email, no photo, no locale, and never the Google ID token (CLAUDE.md rule 13).
 *
 * <p>Written by {@link UserUpsert} rather than through this entity, because a first sign-in
 * and a returning sign-in have to be one statement. This mapping exists for reads, for the
 * existence check on every bearer request, and for deletion.
 */
@Entity
@Table(name = "app_user")
public class UserEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "google_sub", nullable = false, updatable = false)
	private String googleSub;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	// STRING, not ORDINAL: the column is text with a check constraint, and an ordinal would
	// silently remap stored rows if anyone reordered the enum.
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private UserRole role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "last_login_at", nullable = false)
	private Instant lastLoginAt;

	protected UserEntity() {
		// for JPA
	}

	public UUID getId() {
		return id;
	}

	public String getGoogleSub() {
		return googleSub;
	}

	public String getDisplayName() {
		return displayName;
	}

	public UserRole getRole() {
		return role;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public OwnerRef toOwnerRef() {
		return new OwnerRef.User(id);
	}

	/**
	 * Never prints {@link #googleSub()} or {@link #displayName()}.
	 *
	 * <p>Both are on the never-logged list (LLD 12): the {@code sub} identifies a Google
	 * account and the display name is a real person's name. The internal id is safe - it is
	 * not a credential and means nothing outside this database.
	 */
	@Override
	public String toString() {
		return "UserEntity[id=" + id + ", role=" + role + "]";
	}
}
