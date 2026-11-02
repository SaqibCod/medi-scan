package dev.saq.mediscan.extract;

import java.nio.file.Path;

import dev.saq.mediscan.report.SourceType;

/**
 * Where a queued job gets its text from.
 *
 * <p>Lives in {@code extract} rather than {@code upload} because it is the input to
 * extraction: {@code TextExtractor} is defined in terms of it, and {@code extract} must not
 * depend on {@code upload} ({@code backend/CLAUDE.md}, "Dependency direction"). {@code upload}
 * constructs these; {@code analysis} hands them to the registry.
 *
 * <p>Sealed so the registry's dispatch is exhaustive: adding a source - the scanned-image path
 * in phase 4 - has to update every switch over it, rather than falling through a default to
 * "unsupported" at runtime.
 *
 * <p>Every variant that carries text overrides {@code toString()}. A job source is handed to
 * an executor, is named in rejected-execution messages, and is exactly the kind of object that
 * shows up in a thread dump ({@code backend/CLAUDE.md}, "Records print their fields").
 */
public sealed interface JobSource {

	/** What to record in {@code report.source_type} for this source. */
	SourceType sourceType();

	/**
	 * An uploaded PDF, already streamed to a temp file.
	 *
	 * <p>Only the path travels with the job. The original filename is deliberately not kept:
	 * lab PDFs are routinely named after the patient, so carrying it would undo the masking
	 * that happens later, and nothing in the pipeline needs it.
	 */
	record PdfFile(Path path) implements JobSource {

		@Override
		public SourceType sourceType() {
			return SourceType.PDF;
		}
	}

	/**
	 * An uploaded photo, already streamed to a temp file.
	 *
	 * <p>Like {@link PdfFile}, only the path travels: the original filename is not kept, because
	 * a photo of a report is routinely named after the patient or the date it was taken.
	 *
	 * <p>{@code kind} comes from the signature sniff at upload rather than being re-detected
	 * here. It decides nothing about how the file is read - the preparer asks {@code ImageIO}
	 * what the bytes are - but it is worth carrying so a log line can say what was uploaded
	 * without reopening the file.
	 */
	record ImageFile(Path path, ImageKind kind) implements JobSource {

		@Override
		public SourceType sourceType() {
			return SourceType.IMAGE;
		}
	}

	/** The image formats the API accepts (contract 4.1). */
	enum ImageKind {
		PNG,
		JPEG
	}

	/** Text pasted by the caller. Held in memory for the life of the job. */
	record RawText(String text) implements JobSource {

		@Override
		public SourceType sourceType() {
			return SourceType.TEXT;
		}

		@Override
		public String toString() {
			return "RawText[length=" + text.length() + "]";
		}
	}

	/**
	 * One of the bundled samples.
	 *
	 * <p>Carries the text, not just the id, so {@code extract} needs no access to the sample
	 * catalogue - which lives in {@code upload} and would invert the dependency. The id is
	 * kept alongside it for logging and stats.
	 *
	 * <p>{@code LLD} 6.4 has this variant holding only the id, to keep the queue small. The
	 * text is a reference to a string the catalogue already holds for the life of the process,
	 * so carrying it costs a pointer per queued job rather than a copy of the file.
	 */
	record Sample(String sampleId, String text) implements JobSource {

		@Override
		public SourceType sourceType() {
			return SourceType.SAMPLE;
		}

		@Override
		public String toString() {
			return "Sample[sampleId=" + sampleId + ", length=" + text.length() + "]";
		}
	}
}
