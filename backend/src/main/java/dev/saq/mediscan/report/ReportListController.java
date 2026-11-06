package dev.saq.mediscan.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.saq.mediscan.config.CurrentOwner;
import dev.saq.mediscan.session.OwnerRef;

/**
 * {@code GET /api/reports} (contract section 4.5, LLD 5).
 *
 * <p>A separate controller from {@link dev.saq.mediscan.upload.ReportController}, which owns
 * the upload and single-report routes: the history list reads nothing an upload needs, and
 * belongs with the other read-only query classes in this package.
 *
 * <p>{@code status} is typed as {@link ReportStatus} rather than {@code String}, so an
 * unrecognised value fails Spring's own enum conversion with
 * {@code MethodArgumentTypeMismatchException} - already mapped to {@code 400 VALIDATION_ERROR}
 * naming the {@code status} field by {@code ApiExceptionHandler}, with no extra code here.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportListController {

	private final ReportListService service;

	public ReportListController(ReportListService service) {
		this.service = service;
	}

	@GetMapping
	public PageResponse<ReportListItem> list(
			@CurrentOwner OwnerRef owner,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(required = false) Integer size,
			@RequestParam(required = false) ReportStatus status) {

		return service.list(owner, page, size, status);
	}
}
