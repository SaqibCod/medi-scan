package dev.saq.mediscan.report;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * The only place a {@link ReportResponse} is built.
 *
 * <p>One place, because the response shape is the contract, and a second assembler would drift
 * from it. {@code ReportResponseContractTest} compares what this produces field by field with
 * the JSON examples in {@code docs/api-contract.md}, so the contract is checked rather than
 * described.
 *
 * <p>The mapping rules come straight from contract section 4.2: {@code result} only when
 * {@code DONE}, {@code error} only when {@code FAILED}, and both present-but-null otherwise so
 * the client can read either without checking for the key.
 */
@Component
public class ReportResponseMapper {

	/** A report that is still queued or running: no result, no error. */
	public ReportResponse inProgress(ReportEntity report) {
		return new ReportResponse(
				report.getId(),
				report.getStatus(),
				report.getSourceType(),
				report.getCreatedAt(),
				report.getExpiresAt(),
				null,
				null);
	}

	/** A failed report. Still a {@code 200}: the request succeeded, the processing did not. */
	public ReportResponse failed(ReportEntity report) {
		return new ReportResponse(
				report.getId(),
				report.getStatus(),
				report.getSourceType(),
				report.getCreatedAt(),
				report.getExpiresAt(),
				ReportResponse.ReportError.from(report.getErrorCode()),
				null);
	}

	/**
	 * A finished report, with its values and summary.
	 *
	 * @param biomarkers already ordered by {@code position}
	 */
	public ReportResponse done(ReportEntity report, List<BiomarkerEntity> biomarkers,
			ReportSummaryEntity summary) {

		List<ReportResponse.Biomarker> rows = biomarkers.stream()
				.map(ReportResponseMapper::toBiomarker)
				.toList();

		ReportResponse.ReportResult result = new ReportResponse.ReportResult(
				report.getCollectedOn(),
				rows,
				summary != null ? summary.getPatientSummary() : "",
				summary != null ? summary.getHighlights() : List.of(),
				countFlags(biomarkers));

		return new ReportResponse(
				report.getId(),
				report.getStatus(),
				report.getSourceType(),
				report.getCreatedAt(),
				report.getExpiresAt(),
				null,
				result);
	}

	private static ReportResponse.Biomarker toBiomarker(BiomarkerEntity entity) {
		return new ReportResponse.Biomarker(
				entity.getId(),
				entity.getTestName(),
				entity.getRawValue(),
				entity.getNumericValue(),
				entity.getUnit(),
				entity.getReferenceRangeText(),
				entity.getRefLow(),
				entity.getRefHigh(),
				entity.getFlag(),
				entity.getBiomarkerSlug());
	}

	/**
	 * Tallies the flags.
	 *
	 * <p>Computed here rather than by the client, so the number under a results page cannot
	 * disagree with the rows above it. {@code total} includes {@code unknown}, which is what
	 * makes the four sub-counts add up to it.
	 */
	private static ReportResponse.Counts countFlags(List<BiomarkerEntity> biomarkers) {
		int low = 0;
		int normal = 0;
		int high = 0;
		int unknown = 0;

		for (BiomarkerEntity marker : biomarkers) {
			switch (marker.getFlag()) {
				case LOW -> low++;
				case NORMAL -> normal++;
				case HIGH -> high++;
				case UNKNOWN -> unknown++;
			}
		}

		return new ReportResponse.Counts(biomarkers.size(), low, normal, high, unknown);
	}
}
