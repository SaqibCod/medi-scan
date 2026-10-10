package dev.saq.mediscan.report;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.session.OwnerRef;

/**
 * Reads and deletes a caller's own report.
 *
 * <p>Every query here takes an {@link OwnerRef} and goes through an owner-scoped repository
 * method ({@code CLAUDE.md} rule 7). A report belonging to someone else is indistinguishable
 * from one that never existed: both are {@code 404}, so the API never confirms that an id
 * exists. Report ids are random UUIDs, so there is nothing to enumerate either.
 *
 * <p>Each lookup switches over the sealed {@link OwnerRef} rather than passing
 * {@code owner.id()} to a single query. An owner id on its own cannot say which column to
 * filter on, and treating a user id as a session id is exactly the confusion rule 7 exists to
 * prevent. The switches are exhaustive, so a third kind of owner would stop compiling here
 * instead of silently matching nothing.
 */
@Service
public class ReportViewService {

	private static final Logger log = LoggerFactory.getLogger(ReportViewService.class);

	private final ReportRepository reports;
	private final BiomarkerRepository biomarkers;
	private final ReportSummaryRepository summaries;
	private final ReportResponseMapper mapper;
	private final Clock clock;

	public ReportViewService(ReportRepository reports, BiomarkerRepository biomarkers,
			ReportSummaryRepository summaries, ReportResponseMapper mapper, Clock clock) {

		this.reports = reports;
		this.biomarkers = biomarkers;
		this.summaries = summaries;
		this.mapper = mapper;
		this.clock = clock;
	}

	/**
	 * One report, with its results when it has them.
	 *
	 * @throws ApiException {@code 404 REPORT_NOT_FOUND} when the report does not exist for
	 *     this caller - including when it belongs to someone else, or has expired
	 */
	@Transactional(readOnly = true)
	public ReportResponse find(UUID reportId, OwnerRef owner) {
		Instant now = Instant.now(clock);

		ReportEntity report = findOwned(reportId, owner, now)
				.orElseThrow(ReportViewService::notFound);

		return switch (report.getStatus()) {
			case PENDING, PROCESSING -> mapper.inProgress(report);
			case FAILED -> mapper.failed(report);
			case DONE -> mapper.done(report, biomarkersOf(reportId, owner, now),
					summaryOf(reportId, owner, now).orElse(null));
		};
	}

	/**
	 * Deletes a report and everything derived from it.
	 *
	 * <p>Allowed while the report is still processing. The job's next conditional status
	 * update then matches nothing and it stops without saving ({@code LLD} 7.4) - which is why
	 * no coordination with the worker is needed here.
	 *
	 * @throws ApiException {@code 404 REPORT_NOT_FOUND} when there was nothing to delete
	 */
	@Transactional
	public void delete(UUID reportId, OwnerRef owner) {
		int deleted = switch (owner) {
			case OwnerRef.Guest guest -> reports.deleteOwnedBySession(reportId, guest.id());
			case OwnerRef.User user -> reports.deleteOwnedByUser(reportId, user.id());
		};
		if (deleted == 0) {
			throw notFound();
		}
		// The child rows go with it through ON DELETE CASCADE.
		log.info("report deleted id={}", reportId);
	}

	private Optional<ReportEntity> findOwned(UUID reportId, OwnerRef owner, Instant now) {
		return switch (owner) {
			case OwnerRef.Guest guest -> reports.findOwnedBySession(reportId, guest.id(), now);
			case OwnerRef.User user -> reports.findOwnedByUser(reportId, user.id(), now);
		};
	}

	private List<BiomarkerEntity> biomarkersOf(UUID reportId, OwnerRef owner, Instant now) {
		return switch (owner) {
			case OwnerRef.Guest guest -> biomarkers.findOwnedBySession(reportId, guest.id(), now);
			case OwnerRef.User user -> biomarkers.findOwnedByUser(reportId, user.id(), now);
		};
	}

	private Optional<ReportSummaryEntity> summaryOf(UUID reportId, OwnerRef owner, Instant now) {
		return switch (owner) {
			case OwnerRef.Guest guest -> summaries.findOwnedBySession(reportId, guest.id(), now);
			case OwnerRef.User user -> summaries.findOwnedByUser(reportId, user.id(), now);
		};
	}

	/** The same answer for "no such report" and "not yours". */
	private static ApiException notFound() {
		return new ApiException(ErrorCode.REPORT_NOT_FOUND, "That report does not exist.");
	}
}
