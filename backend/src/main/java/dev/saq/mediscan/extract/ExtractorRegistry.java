package dev.saq.mediscan.extract;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;

/**
 * Picks the extractor for a source.
 *
 * <p>Built from every {@link TextExtractor} bean rather than a hard-coded list, so phase 4
 * adds OCR by declaring a component. Nothing here, in the upload validator, or in the job
 * needs to change for images to start working - which is also what makes the phase 2
 * rejection of images honest rather than a special case: no extractor claims {@code IMAGE},
 * so nothing can read one.
 */
@Component
public class ExtractorRegistry {

	private final List<TextExtractor> extractors;

	public ExtractorRegistry(List<TextExtractor> extractors) {
		this.extractors = List.copyOf(extractors);
	}

	/**
	 * Whether any extractor can read this source type.
	 *
	 * <p>Called by the upload layer before a file is written, which is why it takes a type
	 * rather than a source.
	 */
	public boolean supports(SourceType sourceType) {
		return extractors.stream().anyMatch(extractor -> extractor.supports(sourceType));
	}

	/**
	 * Extracts text from {@code source}.
	 *
	 * @throws ReportFailure with {@code UNREADABLE} when nothing can read this source, or
	 *     whatever the chosen extractor raises
	 */
	public ExtractedText extract(JobSource source) {
		return extractors.stream()
				.filter(extractor -> extractor.supports(source))
				.findFirst()
				// Only reachable if a source reaches a job that upload should have refused, so
				// it is a bug rather than a user error - but it still has to end as a report
				// failure rather than an unhandled exception.
				.orElseThrow(() -> new ReportFailure(ReportErrorCode.UNREADABLE))
				.extract(source);
	}
}
