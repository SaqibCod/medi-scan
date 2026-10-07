package dev.saq.mediscan.analysis;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.extract.ExtractedText;
import dev.saq.mediscan.extract.ExtractorRegistry;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.mask.MaskResult;
import dev.saq.mediscan.mask.Masker;
import dev.saq.mediscan.report.ReportResultSaver;
import dev.saq.mediscan.report.ReportStatusService;

/**
 * Runs one report through the pipeline ({@code LLD} 7).
 *
 * <p>Extract, mask, transcribe, validate, summarise, save. The order carries the privacy
 * guarantees, and two points in it are load-bearing:
 *
 * <ul>
 * <li><strong>Raw text stops at masking.</strong> {@link ExtractedText} goes out of scope as
 * soon as {@link MaskResult} exists, and only the masked text is passed on. Nothing after that
 * line can reach the unmasked report ({@code CLAUDE.md} rule 1).</li>
 * <li><strong>Nothing is stored until everything succeeds.</strong> There is one write, at the
 * end. A report that fails anywhere leaves no text, no values and no summary - only its
 * failure code.</li>
 * </ul>
 *
 * <p>The temp file is deleted by {@link ReportJobRunner}'s {@code finally}, not here, so it
 * goes even if this method throws something unexpected.
 */
@Component
public class ReportJob {

	private static final Logger log = LoggerFactory.getLogger(ReportJob.class);

	private final ReportStatusService statuses;
	private final ExtractorRegistry extractors;
	private final Masker masker;
	private final ExtractionStep extractionStep;
	private final BiomarkerValidator validator;
	private final SummaryStep summaryStep;
	private final ReportResultSaver saver;
	private final PipelineObserver observer;

	/**
	 * @param observer optional, so the pipeline runs whether or not anything is counting
	 */
	public ReportJob(ReportStatusService statuses, ExtractorRegistry extractors, Masker masker,
			ExtractionStep extractionStep, BiomarkerValidator validator, SummaryStep summaryStep,
			ReportResultSaver saver, ObjectProvider<PipelineObserver> observer) {

		this.statuses = statuses;
		this.extractors = extractors;
		this.masker = masker;
		this.extractionStep = extractionStep;
		this.validator = validator;
		this.summaryStep = summaryStep;
		this.saver = saver;
		this.observer = observer.getIfAvailable(() -> conflicts -> {
		});
	}

	/**
	 * Processes {@code reportId}.
	 *
	 * @return the outcome, for the runner to record
	 * @throws ReportFailure when a stage fails in a way the user should be told about
	 */
	public Outcome run(UUID reportId, JobSource source) {
		if (!statuses.markProcessing(reportId)) {
			// Deleted, expired, or already claimed by another worker. Stop without writing
			// anything (LLD 7.1).
			log.info("report not claimable id={}", reportId);
			return Outcome.SKIPPED;
		}

		String maskedText = extractAndMask(reportId, source);

		ModelExtraction extracted = extractionStep.run(maskedText);
		ValidationOutcome validated = validator.validate(extracted, maskedText);

		log.info("validate done id={} kept={} dropped={}",
				reportId, validated.biomarkers().size(), validated.dropsByReason());

		if (validated.isEmpty()) {
			// A readable document with nothing in it that survived checking. Distinct from
			// UNREADABLE, which is about the text itself.
			throw new ReportFailure(ReportErrorCode.NO_RESULTS_FOUND);
		}

		SummaryStep.ReportSummary summary = summaryStep.run(validated.biomarkers());

		boolean saved = saver.saveDone(reportId, maskedText, toRows(validated.biomarkers()),
				summary.patientSummary(), summary.highlights(), validated.collectedOn());

		return saved ? Outcome.DONE : Outcome.SKIPPED;
	}

	/**
	 * Extraction and masking, in a scope that ends with the raw text.
	 *
	 * <p>A separate method so the unmasked {@link ExtractedText} is unreachable from the rest
	 * of {@link #run}: the compiler, not a comment, is what keeps it out of the later stages.
	 */
	private String extractAndMask(UUID reportId, JobSource source) {
		ExtractedText extracted = extractors.extract(source);
		log.info("extract done id={} pages={} lowTextPages={} chars={}",
				reportId, extracted.pageCount(), extracted.lowTextPages(), extracted.charCount());

		MaskResult masked = masker.mask(extracted.text());
		log.info("mask done id={} counts={} conflicts={}",
				reportId, masked.counts(), masked.conflicts());

		observer.maskingCompleted(masked.conflicts());

		return masked.maskedText();
	}

	private static List<ReportResultSaver.BiomarkerRow> toRows(List<ValidatedBiomarker> markers) {
		return markers.stream()
				.map(marker -> new ReportResultSaver.BiomarkerRow(
						marker.position(), marker.testName(), marker.testNameNorm(),
						marker.biomarkerSlug(), marker.rawValue(), marker.numericValue(),
						marker.unit(), marker.referenceRangeText(), marker.refLow(),
						marker.refHigh(), marker.flag()))
				.toList();
	}

	/** What happened to the report. */
	public enum Outcome {

		/** Processed and saved. */
		DONE,

		/**
		 * Nothing was done, because the report was gone.
		 *
		 * <p>Not a failure: no status is written, because there is no row to write it to.
		 */
		SKIPPED
	}

	/**
	 * Where the job reports things worth counting.
	 *
	 * <p>An interface so the job does not depend on {@code stats}. The real implementation
	 * arrives with the stats recorder; until then a no-op is supplied.
	 */
	public interface PipelineObserver {

		void maskingCompleted(int conflicts);
	}
}
