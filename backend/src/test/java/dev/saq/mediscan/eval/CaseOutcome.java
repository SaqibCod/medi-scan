package dev.saq.mediscan.eval;

import java.time.LocalDate;
import java.util.List;

import dev.saq.mediscan.analysis.ValidatedBiomarker;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.report.ReportStatus;

/**
 * What one case's run actually produced, read back from the database the same way
 * {@code EvalRunner} will. Kept separate from {@link EvalCase}/{@link ExpectedResult} - the
 * answer key - so a metrics class is visibly comparing "what happened" against "what should
 * have happened" rather than mutating one object into the other.
 *
 * @param producedRows empty for a {@code FAILED} case
 */
public record CaseOutcome(
		EvalCase evalCase,
		ExpectedResult expected,
		ReportStatus actualStatus,
		ReportErrorCode actualErrorCode,
		LocalDate actualCollectedOn,
		List<ValidatedBiomarker> producedRows) {

	public CaseOutcome {
		producedRows = producedRows == null ? List.of() : List.copyOf(producedRows);
	}
}
