package dev.saq.mediscan.upload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.saq.mediscan.report.SourceType;

/**
 * Declared-type agreement and magic-byte detection ({@code LLD} 6.2, steps 4 and 5).
 *
 * <p>These bytes get handed to a PDF parser, and from phase 4 to an image decoder. Feeding a
 * parser something that is not what it claims to be is how parser bugs turn into exploits, so
 * what counts as "this really is a PDF" is worth pinning down.
 */
class FileTypeTest {

	@ParameterizedTest(name = "{0} + {1} -> {2}")
	@CsvSource({
			"application/pdf, report.pdf, PDF",
			"application/pdf, REPORT.PDF, PDF",
			"'application/pdf; charset=binary', report.pdf, PDF",
			"image/png, scan.png, PNG",
			"image/jpeg, scan.jpg, JPEG",
			"image/jpeg, scan.jpeg, JPEG",
			"image/jpeg, scan.JPEG, JPEG",
	})
	@DisplayName("accepts a type the content type and filename agree on")
	void acceptsAgreeingDeclarations(String contentType, String filename, FileType expected) {
		assertThat(FileType.fromDeclared(contentType, filename)).contains(expected);
	}

	@ParameterizedTest(name = "{0} + {1}")
	@CsvSource({
			// Disagreement: either a confused client or someone probing. Refused either way.
			"application/pdf, report.png",
			"image/png, report.pdf",
			// Unsupported types, however they are declared.
			"application/zip, report.zip",
			"text/plain, report.txt",
			"application/octet-stream, report.pdf",
			"application/pdf, report.exe",
			// Missing halves.
			"application/pdf, ''",
	})
	@DisplayName("refuses anything the two declarations do not agree on")
	void refusesDisagreeingDeclarations(String contentType, String filename) {
		assertThat(FileType.fromDeclared(contentType, filename)).isEmpty();
	}

	@Test
	@DisplayName("refuses a missing content type or filename")
	void refusesMissingDeclarations() {
		assertThat(FileType.fromDeclared(null, "report.pdf")).isEmpty();
		assertThat(FileType.fromDeclared("application/pdf", null)).isEmpty();
		assertThat(FileType.fromDeclared(null, null)).isEmpty();
	}

	@Test
	@DisplayName("detects a PDF by its signature")
	void detectsPdf() {
		byte[] header = "%PDF-1.7".getBytes();

		assertThat(FileType.fromSignature(header)).contains(FileType.PDF);
	}

	@Test
	@DisplayName("detects PNG and JPEG by their signatures")
	void detectsImages() {
		byte[] png = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
		byte[] jpeg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0 };

		assertThat(FileType.fromSignature(png)).contains(FileType.PNG);
		assertThat(FileType.fromSignature(jpeg)).contains(FileType.JPEG);
	}

	@Test
	@DisplayName("refuses a file whose bytes match nothing")
	void refusesUnknownSignature() {
		assertThat(FileType.fromSignature("PK a zip file".getBytes())).isEmpty();
		assertThat(FileType.fromSignature("just some text".getBytes())).isEmpty();
	}

	@Test
	@DisplayName("refuses a truncated or empty header rather than reading past it")
	void refusesShortHeader() {
		// A 2-byte file cannot match an 8-byte signature, and must not index out of bounds
		// while finding that out.
		assertThat(FileType.fromSignature(new byte[] { 0x25, 0x50 })).isEmpty();
		assertThat(FileType.fromSignature(new byte[0])).isEmpty();
		assertThat(FileType.fromSignature(null)).isEmpty();
	}

	@Test
	@DisplayName("a PDF renamed to .pdf but holding PNG bytes is caught by the signature")
	void catchesMismatchedContent() {
		// The declaration agrees with itself, so step 4 passes. Step 5 is what catches it.
		assertThat(FileType.fromDeclared("application/pdf", "report.pdf")).contains(FileType.PDF);

		byte[] actuallyPng = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
		assertThat(FileType.fromSignature(actuallyPng)).contains(FileType.PNG);
	}

	@Test
	@DisplayName("maps to the source type recorded on the report")
	void mapsToSourceType() {
		assertThat(FileType.PDF.sourceType()).isEqualTo(SourceType.PDF);
		assertThat(FileType.PNG.sourceType()).isEqualTo(SourceType.IMAGE);
		assertThat(FileType.JPEG.sourceType()).isEqualTo(SourceType.IMAGE);
	}
}
