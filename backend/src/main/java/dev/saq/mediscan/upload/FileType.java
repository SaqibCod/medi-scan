package dev.saq.mediscan.upload;

import java.util.Locale;
import java.util.Optional;

import dev.saq.mediscan.report.SourceType;

/**
 * The upload types the API accepts, with the magic bytes that prove what a file really is.
 *
 * <p>Both the declared content type and the filename extension have to agree on one of these,
 * and then the file's own first bytes have to agree too (contract section 4.1). A content type
 * is whatever the client chose to send, and an extension is part of a name the user typed -
 * neither is evidence. The signature is.
 *
 * <p>That matters more here than in a typical upload: the bytes get handed to a PDF parser,
 * and from phase 4 to an image decoder. Feeding a parser something that is not what it claims
 * to be is how parser bugs become exploits, so the type is settled before anything opens it.
 */
public enum FileType {

	/** {@code %PDF-} */
	PDF(SourceType.PDF, "application/pdf", new byte[] { 0x25, 0x50, 0x44, 0x46, 0x2D }, ".pdf"),

	/** The 8-byte PNG signature. */
	PNG(SourceType.IMAGE, "image/png",
			new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }, ".png"),

	/** JPEG's SOI marker plus the first byte of the next marker. */
	JPEG(SourceType.IMAGE, "image/jpeg",
			new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF }, ".jpg", ".jpeg");

	/** Enough bytes for the longest signature above. */
	public static final int SIGNATURE_LENGTH = 8;

	private final SourceType sourceType;
	private final String contentType;
	private final byte[] signature;
	private final String[] extensions;

	FileType(SourceType sourceType, String contentType, byte[] signature, String... extensions) {
		this.sourceType = sourceType;
		this.contentType = contentType;
		this.signature = signature;
		this.extensions = extensions;
	}

	public SourceType sourceType() {
		return sourceType;
	}

	/**
	 * The type a declared content type and filename agree on.
	 *
	 * <p>Both must point at the same type. Empty means the upload is rejected with
	 * {@code 415 UNSUPPORTED_FILE_TYPE} before anything reads a byte of it.
	 */
	public static Optional<FileType> fromDeclared(String contentType, String filename) {
		Optional<FileType> byContentType = fromContentType(contentType);
		Optional<FileType> byExtension = fromFilename(filename);

		if (byContentType.isEmpty() || byExtension.isEmpty()) {
			return Optional.empty();
		}
		// Disagreement is itself a reason to refuse: a .pdf sent as image/png is either a
		// confused client or someone probing.
		return byContentType.equals(byExtension) ? byContentType : Optional.empty();
	}

	/** The type whose signature matches {@code header}, if any. */
	public static Optional<FileType> fromSignature(byte[] header) {
		for (FileType type : values()) {
			if (type.matches(header)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}

	private static Optional<FileType> fromContentType(String contentType) {
		if (contentType == null) {
			return Optional.empty();
		}
		// Strip any parameters, so "application/pdf; charset=binary" still matches.
		String bare = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
		for (FileType type : values()) {
			if (type.contentType.equals(bare)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}

	private static Optional<FileType> fromFilename(String filename) {
		if (filename == null || filename.isBlank()) {
			return Optional.empty();
		}
		String lower = filename.toLowerCase(Locale.ROOT);
		for (FileType type : values()) {
			for (String extension : type.extensions) {
				if (lower.endsWith(extension)) {
					return Optional.of(type);
				}
			}
		}
		return Optional.empty();
	}

	private boolean matches(byte[] header) {
		if (header == null || header.length < signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if (header[i] != signature[i]) {
				return false;
			}
		}
		return true;
	}
}
