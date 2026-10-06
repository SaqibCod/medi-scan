package dev.saq.mediscan.upload;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ProblemDetails;
import dev.saq.mediscan.config.ValidationException;
import dev.saq.mediscan.extract.ExtractorRegistry;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.report.SourceType;

/**
 * Everything {@code POST /api/reports} refuses, and the order it refuses it in.
 *
 * <p>The order is from {@code LLD} 6.2 and is deliberate rather than incidental. Cheap checks
 * on data the caller sent come first, then checks that need the file's bytes, then checks that
 * need the catalogue. The effect is that a request is rejected at the earliest point it can
 * be, so nothing expensive - a temp file, a database row, a queue slot - is spent on a request
 * that was never going to be accepted.
 *
 * <p>Consent is checked before anything else for a different reason: a caller who has not
 * consented should be refused before their file is read at all, let alone written to disk.
 */
@Component
public class UploadValidator {

	private final MediScanProperties.Upload limits;
	private final SampleCatalog samples;
	private final ExtractorRegistry extractors;

	public UploadValidator(MediScanProperties properties, SampleCatalog samples,
			ExtractorRegistry extractors) {
		this.limits = properties.upload();
		this.samples = samples;
		this.extractors = extractors;
	}

	/**
	 * Step 1 for both request shapes: consent must be explicitly {@code true}.
	 *
	 * <p>{@code null} and {@code false} are told apart in the message only - both are refused.
	 */
	public void requireConsent(Boolean consent) {
		if (consent == null) {
			throw validationError("consent", "is required");
		}
		if (!consent) {
			throw validationError("consent", "must be true");
		}
	}

	/** The multipart form's {@code consent} part, which arrives as a string. */
	public void requireConsent(String consent) {
		if (consent == null || consent.isBlank()) {
			throw validationError("consent", "is required");
		}
		if (!"true".equalsIgnoreCase(consent.strip())) {
			throw validationError("consent", "must be true");
		}
	}

	/**
	 * Steps 2 to 4 for a file upload: present, within the size limit, and a type both the
	 * content type and the filename agree on.
	 *
	 * <p>The size check here uses the multipart part's declared size, which is a fast
	 * rejection for the honest case. {@link TempFileStore#write} counts the bytes it actually
	 * writes, which is the check that holds when the declared size is missing or wrong.
	 *
	 * @return the type the caller declared, to be confirmed against the file's own bytes
	 */
	public FileType validateDeclaredFile(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw validationError("file", "is required");
		}
		if (file.getSize() > limits.maxBytes()) {
			throw new ApiException(ErrorCode.FILE_TOO_LARGE,
					"The file is larger than the " + limits.maxBytes() + " byte limit.");
		}

		return FileType.fromDeclared(file.getContentType(), file.getOriginalFilename())
				.orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
						"Only PDF, PNG and JPEG files are accepted."));
	}

	/**
	 * Steps 5 and 6: the file's own first bytes, then whether anything can read that type yet.
	 *
	 * <p>A mismatch is a {@code 400}, not a {@code 415}: the type is one the API supports, but
	 * the file is not what it says it is. The distinction matters to the client, which shows a
	 * different message for "we don't accept this kind of file" than for "this file looks
	 * corrupt".
	 *
	 * <p>The {@code UNSUPPORTED_FILE_TYPE} at the end is how images are refused in phase 2:
	 * the signature is a valid PNG, and the only thing wrong with it is that no extractor can
	 * read an image until OCR ships (contract section 4.1). Expressing it as "no extractor
	 * supports this" rather than "if PNG then reject" means phase 4 turns it on by registering
	 * an extractor, with nothing here to remember to change.
	 */
	public FileType validateSignature(byte[] header, FileType declared) {
		FileType actual = FileType.fromSignature(header)
				.orElseThrow(() -> new ApiException(ErrorCode.FILE_SIGNATURE_MISMATCH,
						"The file's content does not match its type."));

		if (actual != declared) {
			throw new ApiException(ErrorCode.FILE_SIGNATURE_MISMATCH,
					"The file's content does not match its type.");
		}
		if (!extractors.supports(actual.sourceType())) {
			throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
					"This file type cannot be read yet. Try a text-based PDF, or paste the text.");
		}
		return actual;
	}

	/**
	 * The JSON body: consent, exactly one source, and that source's own rules.
	 *
	 * @return the job source and the source type to record on the report
	 */
	public ValidatedJsonRequest validateJson(CreateReportRequest request) {
		requireConsent(request.consent());

		boolean hasText = request.text() != null && !request.text().isBlank();
		boolean hasSample = request.sampleId() != null && !request.sampleId().isBlank();

		if (hasText == hasSample) {
			// Covers both and neither. One message for both cases, because the fix is the
			// same: send exactly one.
			throw new ValidationException("Send exactly one of text or sampleId.", List.of(
					new ProblemDetails.FieldError("text", "exactly one of text or sampleId is required"),
					new ProblemDetails.FieldError("sampleId", "exactly one of text or sampleId is required")));
		}

		if (hasSample) {
			// 404, not 400: the field is well-formed, the thing it names does not exist.
			Sample sample = samples.find(request.sampleId())
					.orElseThrow(() -> new ApiException(ErrorCode.SAMPLE_NOT_FOUND,
							"That sample does not exist."));
			return new ValidatedJsonRequest(
					new JobSource.Sample(sample.id(), sample.text()), SourceType.SAMPLE);
		}

		String text = request.text().strip();
		if (text.length() < limits.minTextChars()) {
			throw validationError("text",
					"must be at least " + limits.minTextChars() + " characters");
		}
		if (text.length() > limits.maxTextChars()) {
			throw validationError("text",
					"must be at most " + limits.maxTextChars() + " characters");
		}
		return new ValidatedJsonRequest(new JobSource.RawText(text), SourceType.TEXT);
	}

	private static ValidationException validationError(String field, String message) {
		return new ValidationException("One or more fields are invalid.",
				List.of(new ProblemDetails.FieldError(field, message)));
	}

	/**
	 * A JSON request that passed validation.
	 *
	 * <p>{@code toString} is inherited from {@link JobSource.RawText}, which already hides the
	 * text, so this record needs no override of its own.
	 */
	public record ValidatedJsonRequest(JobSource source, SourceType sourceType) {
	}
}
