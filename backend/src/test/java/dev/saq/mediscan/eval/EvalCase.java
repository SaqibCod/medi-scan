package dev.saq.mediscan.eval;

import java.util.List;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.report.ReportStatus;

/**
 * One evaluation case: its metadata from {@code case.json} and its report text from
 * {@code report.txt} ({@code docs/phase-3-lld.md} section 4.1).
 *
 * <p>{@code expectedStatus} is always {@code DONE} or {@code FAILED} - {@link ReportStatus}
 * also has {@code PENDING} and {@code PROCESSING}, which no case should ever expect, and
 * {@code DatasetConsistencyTest} enforces that.
 */
public record EvalCase(
		String id,
		String title,
		boolean synthetic,
		Format format,
		List<String> tags,
		ReportStatus expectedStatus,
		ReportErrorCode expectedErrorCode,
		String reportText) {

	public EvalCase {
		tags = tags == null ? List.of() : List.copyOf(tags);
	}

	/** How {@code report.txt} should reach the pipeline. */
	public enum Format {

		/** Sent to the job as {@code JobSource.RawText}. */
		TEXT,

		/** Rendered into a PDF at test time and sent as {@code JobSource.PdfFile}. */
		PDF
	}
}
