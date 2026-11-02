package dev.saq.mediscan.analysis;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.extract.ConfidenceIndex;
import dev.saq.mediscan.extract.ExtractedText;
import dev.saq.mediscan.extract.ExtractorRegistry;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.mask.MaskResult;
import dev.saq.mediscan.mask.Masker;
import dev.saq.mediscan.report.ReportResultSaver;
import dev.saq.mediscan.report.ReportStatusService;
import dev.saq.mediscan.report.TextSource;

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
		this.observer = observer.getIfAvailable(() -> PipelineObserver.NONE);
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

		MaskedInput input = extractAndMask(reportId, source);
		String maskedText = input.maskedText();

		ModelExtraction extracted = extractionStep.run(maskedText);
		ValidationOutcome validated =
				validator.validate(extracted, maskedText, input.confidence());

		log.info("validate done id={} kept={} dropped={} rangeTextDiscarded={} "
				+ "lowConfidenceValues={} unlocatedValues={}",
				reportId, validated.biomarkers().size(), validated.dropsByReason(),
				validated.rangeTextDiscarded(), validated.lowConfidenceValues(),
				validated.unlocatedValues());

		observer.lowConfidenceValuesFound(validated.lowConfidenceValues());

		if (validated.isEmpty()) {
			// A readable document with nothing in it that survived checking. Distinct from
			// UNREADABLE, which is about the text itself.
			throw new ReportFailure(ReportErrorCode.NO_RESULTS_FOUND);
		}

		SummaryStep.ReportSummary summary = summaryStep.run(validated.biomarkers());

		boolean saved = saver.saveDone(reportId, maskedText, toRows(validated.biomarkers()),
				summary.patientSummary(), summary.highlights(), validated.collectedOn(),
				input.textSource());

		return saved ? Outcome.DONE : Outcome.SKIPPED;
	}

	/**
	 * Extraction and masking, in a scope that ends with the raw text.
	 *
	 * <p>A separate method so the unmasked {@link ExtractedText} is unreachable from the rest
	 * of {@link #run}: the compiler, not a comment, is what keeps it out of the later stages.
	 *
	 * <p>Phase 4 needs two things from extraction to outlive this scope - how the text was read,
	 * and (later) the OCR confidence - so it returns a {@link MaskedInput} rather than a bare
	 * string. The raw text is still left behind here, which is the whole point of the method.
	 */
	private MaskedInput extractAndMask(UUID reportId, JobSource source) {
		ExtractedText extracted = extractors.extract(source);
		log.info("extract done id={} pages={} lowTextPages={} textSource={} chars={}",
				reportId, extracted.pageCount(), extracted.lowTextPages(),
				extracted.textSource(), extracted.charCount());

		MaskResult masked = masker.mask(extracted.text());
		log.info("mask done id={} counts={} conflicts={}",
				reportId, masked.counts(), masked.conflicts());

		observer.maskingCompleted(masked.conflicts());

		return new MaskedInput(masked.maskedText(), extracted.textSource(),
				extracted.confidence());
	}

	/**
	 * What survives extraction: the masked text, and what is known about how it was read.
	 *
	 * <p>The confidence index outlives the raw text deliberately, and it is safe for it to:
	 * it holds OCR's lines, which for a result row are byte-identical to the masked text's, and
	 * it is only ever asked about a value the model already transcribed. Nothing can read the
	 * unmasked parts of the report back out of it.
	 *
	 * <p>{@code toString()} hides the text. This record is a method-local result rather than
	 * anything long-lived, but it holds the masked report and records print their fields
	 * ({@code backend/CLAUDE.md}, "Records print their fields").
	 */
	private record MaskedInput(String maskedText, TextSource textSource,
			ConfidenceIndex confidence) {

		@Override
		public String toString() {
			return "MaskedInput[textSource=" + textSource + ", chars=" + maskedText.length()
					+ ", confidence=" + confidence + "]";
		}
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

		/** Counts nothing, for when no stats recorder is present (its unit tests, and tests). */
		PipelineObserver NONE = new PipelineObserver() {

			@Override
			public void maskingCompleted(int conflicts) {
			}

			@Override
			public void lowConfidenceValuesFound(int count) {
			}
		};

		void maskingCompleted(int conflicts);

		/**
		 * How many rows the OCR low-confidence rule turned {@code UNKNOWN} on this report.
		 *
		 * <p>A count, never which rows. It is the only measure of what the rule costs readers,
		 * and a sudden rise in it is how a degraded OCR install or a bad {@code --psm} choice
		 * announces itself ({@code docs/phase-4-lld.md} section 8).
		 */
		void lowConfidenceValuesFound(int count);
	}
}
