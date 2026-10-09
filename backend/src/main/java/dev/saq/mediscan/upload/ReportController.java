package dev.saq.mediscan.upload;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportResponse;
import dev.saq.mediscan.report.ReportViewService;
import dev.saq.mediscan.session.GuestPrincipal;

/**
 * {@code POST /api/reports} (contract section 4.1).
 *
 * <p>Two handlers for the POST, split by {@code consumes}, because the request has two
 * genuinely different shapes: a multipart form with a file, and a JSON body with text or a
 * sample id. One handler taking both would have to hand-parse the content type and then
 * branch, which is the same decision made worse.
 *
 * <p>Thin, per {@code backend/CLAUDE.md}: take the principal, call one service method, map the
 * result. Validation lives in {@link UploadValidator} and ordering in
 * {@link CreateReportService}, both of which are unit-testable without a request.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

	private final CreateReportService createReport;
	private final ReportViewService reportView;

	public ReportController(CreateReportService createReport, ReportViewService reportView) {
		this.createReport = createReport;
		this.reportView = reportView;
	}

	/** Option A: a PDF, PNG or JPEG upload. Images are refused until phase 4. */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<CreateReportResponse> createFromFile(
			@AuthenticationPrincipal GuestPrincipal principal,
			@RequestPart("file") MultipartFile file,
			// Not @RequestPart: a plain form field has no content type, and @RequestPart
			// would try to convert it as one. Required is false so a missing consent reaches
			// the validator and becomes a field error rather than a bare 400.
			@RequestParam(name = "consent", required = false) String consent) {

		ReportEntity report = createReport.createFromFile(principal.sessionId(), file, consent);
		return accepted(report);
	}

	/** Options B and C: pasted text, or a sample id. */
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<CreateReportResponse> createFromJson(
			@AuthenticationPrincipal GuestPrincipal principal,
			@RequestBody CreateReportRequest request) {

		ReportEntity report = createReport.createFromJson(principal.sessionId(), request);
		return accepted(report);
	}

	/**
	 * {@code GET /api/reports/{id}} (contract section 4.2).
	 *
	 * <p>The client polls this until {@code status} is terminal. A failed report is a
	 * {@code 200} with an {@code error} object: the request succeeded, the processing did not.
	 */
	@GetMapping("/{id}")
	public ReportResponse find(@AuthenticationPrincipal GuestPrincipal principal,
			@PathVariable UUID id) {

		return reportView.find(id, principal.toOwnerRef());
	}

	/**
	 * {@code DELETE /api/reports/{id}} (contract section 4.3).
	 *
	 * <p>{@code 204} with no body. Allowed while the report is still processing: the job's
	 * next conditional status update finds nothing and it stops without saving.
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal GuestPrincipal principal,
			@PathVariable UUID id) {

		reportView.delete(id, principal.toOwnerRef());
	}

	/**
	 * {@code 202} with a {@code Location} header, because processing is asynchronous: the
	 * report exists and is queued, and the client polls the returned URL for its status.
	 */
	private static ResponseEntity<CreateReportResponse> accepted(ReportEntity report) {
		return ResponseEntity
				.accepted()
				.location(URI.create("/api/reports/" + report.getId()))
				.body(CreateReportResponse.from(report));
	}
}
