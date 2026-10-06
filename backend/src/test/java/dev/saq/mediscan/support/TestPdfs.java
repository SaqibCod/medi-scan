package dev.saq.mediscan.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Builds PDFs for tests, in code.
 *
 * <p>Generated rather than committed as fixtures, per {@code backend/CLAUDE.md}: a test that
 * says "a PDF with no text layer" is clearer than a binary blob whose contents have to be
 * taken on trust, and the inputs stay visible next to the assertion.
 */
public final class TestPdfs {

	/** Standard 14 font, so nothing has to be embedded. */
	private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

	private TestPdfs() {
	}

	/** A one-page PDF containing {@code lines} as real, extractable text. */
	public static byte[] withText(List<String> lines) {
		return withPages(List.of(lines));
	}

	/** A PDF with one page per element, each holding that element's lines. */
	public static byte[] withPages(List<List<String>> pages) {
		try (PDDocument document = new PDDocument()) {
			for (List<String> lines : pages) {
				addTextPage(document, lines);
			}
			return toBytes(document);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build a test PDF", ex);
		}
	}

	/**
	 * A PDF whose pages carry a raster image and no text at all.
	 *
	 * <p>This is what a scanned report looks like to PDFBox: valid pages, real content, and
	 * nothing a text stripper can return. In phase 2 it must fail {@code UNREADABLE}; phase 4
	 * is where it starts working.
	 */
	public static byte[] imageOnly() {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);

			PDImageXObject image = LosslessFactory.createFromImage(document, scanLikeImage());
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.drawImage(image, 40, 400, 520, 360);
			}
			return toBytes(document);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build an image-only test PDF", ex);
		}
	}

	/** A password-protected PDF, which the extractor must refuse as {@code UNREADABLE}. */
	public static byte[] encrypted() {
		try (PDDocument document = new PDDocument()) {
			addTextPage(document, List.of("Total Cholesterol 238 mg/dL <200"));

			AccessPermission permissions = new AccessPermission();
			// A user password means the document cannot be opened without it, which is the
			// case the extractor has to survive.
			document.protect(new StandardProtectionPolicy("owner-pw", "user-pw", permissions));

			return toBytes(document);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build an encrypted test PDF", ex);
		}
	}

	/** Writes {@code bytes} to a temp file and returns its path. */
	public static Path toTempFile(Path directory, byte[] bytes) {
		try {
			Files.createDirectories(directory);
			Path file = Files.createTempFile(directory, "test-", ".pdf");
			Files.write(file, bytes);
			return file;
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not write a test PDF", ex);
		}
	}

	private static void addTextPage(PDDocument document, List<String> lines) throws IOException {
		PDPage page = new PDPage();
		document.addPage(page);

		try (PDPageContentStream content = new PDPageContentStream(document, page)) {
			content.beginText();
			content.setFont(FONT, 11);
			content.newLineAtOffset(40, 740);
			for (String line : lines) {
				content.showText(line);
				// Leading has to be set before it is used, and 14pt matches an 11pt font.
				content.setLeading(14);
				content.newLine();
			}
			content.endText();
		}
	}

	/** A plain grey block: enough to be a real image without being a real document. */
	private static BufferedImage scanLikeImage() {
		BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, 600, 400);
			graphics.setColor(Color.DARK_GRAY);
			// Horizontal bars, so it looks like lines of text to a human and like nothing at
			// all to a text stripper.
			for (int y = 40; y < 360; y += 40) {
				graphics.fillRect(40, y, 480, 12);
			}
		}
		finally {
			graphics.dispose();
		}
		return image;
	}

	private static byte[] toBytes(PDDocument document) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		document.save(out);
		return out.toByteArray();
	}
}
