package dev.saq.mediscan.extract;

import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;

/**
 * Turns one kind of {@link JobSource} into text.
 *
 * <p>Implementations are plain classes: no Spring MVC, no JPA, no knowledge of reports or
 * jobs. They take a source and return text or throw {@link ReportFailure}
 * ({@code backend/CLAUDE.md}, "Dependency direction").
 */
public interface TextExtractor {

	/** Whether this extractor can read {@code source}. */
	boolean supports(JobSource source);

	/**
	 * Whether this extractor can read the given source type at all.
	 *
	 * <p>Separate from {@link #supports(JobSource)} so the upload layer can answer "will
	 * anything be able to read a PNG?" before it has written a file to ask about. That is how
	 * images are refused in phase 2 (contract section 4.1) without the rejection being
	 * hard-coded anywhere: no extractor claims {@code IMAGE}, so the upload is refused, and
	 * phase 4 turns it on by registering one.
	 */
	boolean supports(SourceType sourceType);

	/**
	 * Extracts the text.
	 *
	 * @throws ReportFailure with {@code UNREADABLE} when there is no usable text, or
	 *     {@code DOCUMENT_TOO_LONG} when the document is over the character or page limit
	 */
	ExtractedText extract(JobSource source);
}
