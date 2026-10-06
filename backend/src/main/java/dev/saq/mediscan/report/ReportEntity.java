package dev.saq.mediscan.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import dev.saq.mediscan.session.OwnerRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One report row.
 *
 * <p>Holds no report content: the masked text, the values and the summary live in their own
 * tables, written only when the job succeeds. That is what lets a failed report store
 * nothing but its own failure code.
 *
 * <p>{@code session_id} is the owner in phase 2. Queries never filter on the id alone - see
 * {@link ReportRepository} and {@link OwnerRef}.
 */
@Entity
@Table(name = "report")
public class ReportEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "session_id", nullable = false, updatable = false)
	private UUID sessionId;

	// STRING, not ORDINAL: the column is text with a check constraint, and an ordinal would
	// silently remap every stored row if anyone reordered the enum.
	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, updatable = false)
	private SourceType sourceType;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ReportStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "error_code")
	private ReportErrorCode errorCode;

	@Column(name = "collected_on")
	private LocalDate collectedOn;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected ReportEntity() {
		// for JPA
	}

	/** A freshly accepted report: {@code PENDING}, with no timings and no results yet. */
	public ReportEntity(UUID id, UUID sessionId, SourceType sourceType, Instant createdAt, Instant expiresAt) {
		this.id = id;
		this.sessionId = sessionId;
		this.sourceType = sourceType;
		this.status = ReportStatus.PENDING;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public SourceType getSourceType() {
		return sourceType;
	}

	public ReportStatus getStatus() {
		return status;
	}

	public ReportErrorCode getErrorCode() {
		return errorCode;
	}

	public LocalDate getCollectedOn() {
		return collectedOn;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	/**
	 * Safe to log and safe to print in a debugger: every field here is an id, an enum or a
	 * timestamp. Nothing on this entity is report content, which is why it needs no
	 * redaction - unlike the text and biomarker types.
	 */
	@Override
	public String toString() {
		return "ReportEntity[id=" + id + ", status=" + status + ", sourceType=" + sourceType
				+ ", errorCode=" + errorCode + "]";
	}
}
