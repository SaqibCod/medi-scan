package dev.saq.mediscan.report;

import java.time.Clock;
import java.time.Instant;
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

		ReportEntity report = reports.findOwnedBySession(reportId, owner.id(), now)
				.orElseThrow(ReportViewService::notFound);

		return switch (report.getStatus()) {
			case PENDING, PROCESSING -> mapper.inProgress(report);
			case FAILED -> mapper.failed(report);
			case DONE -> mapper.done(report,
					biomarkers.findOwnedBySession(reportId, owner.id(), now),
					summaries.findOwnedBySession(reportId, owner.id(), now).orElse(null));
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
		int deleted = reports.deleteOwnedBySession(reportId, owner.id());
		if (deleted == 0) {
			throw notFound();
		}
		// The child rows go with it through ON DELETE CASCADE.
		log.info("report deleted id={}", reportId);
	}

	/** The same answer for "no such report" and "not yours". */
	private static ApiException notFound() {
		return new ApiException(ErrorCode.REPORT_NOT_FOUND, "That report does not exist.");
	}
}
