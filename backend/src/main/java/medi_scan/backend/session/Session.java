package medi_scan.backend.session;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A guest session.
 *
 * <p>Holds the SHA-256 hash of the session token, never the token itself, so this table is
 * useless to anyone who obtains a copy of it ({@code CLAUDE.md} rule 14).
 *
 * <p>An entity rather than a record because JPA requires a mutable no-arg class. DTOs are
 * records - see {@link SessionResponse}.
 */
@Entity
@Table(name = "session")
public class Session {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64)
	private String tokenHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected Session() {
		// for JPA
	}

	public Session(UUID id, String tokenHash, Instant createdAt, Instant expiresAt) {
		this.id = id;
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public UUID getId() {
		return id;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	/** The owner reference to pass to report queries for this session's caller. */
	public OwnerRef toOwnerRef() {
		return new OwnerRef.Guest(id);
	}

	/**
	 * Deliberately excludes {@code tokenHash}: an entity's {@code toString} ends up in log
	 * lines and debugger output, and credential material should not.
	 */
	@Override
	public String toString() {
		return "Session[id=" + id + ", expiresAt=" + expiresAt + "]";
	}
}
