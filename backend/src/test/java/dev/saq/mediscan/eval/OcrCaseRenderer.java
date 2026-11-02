package dev.saq.mediscan.eval;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.w3c.dom.Node;

/**
 * Turns an evaluation case's text into the kind of file a person actually uploads
 * ({@code docs/phase-4-lld.md} 10.1).
 *
 * <p><strong>Why render rather than commit fixtures.</strong> The answer keys in
 * {@code eval/cases} were written by reading the report text. Rendering that same text means
 * every OCR case reuses its key unchanged, so what the numbers measure is the OCR path alone -
 * nothing was re-keyed, and a disagreement cannot be explained away as a different report. It
 * also keeps forty-odd synthetic photographs out of the repository.
 *
 * <p><strong>Reproducible.</strong> Every variant that needs randomness takes it from a seed
 * derived from the case id, so the same case renders byte-for-byte the same image on every run
 * and a score that moves means the pipeline moved.
 *
 * <p><strong>Fonts.</strong> Only Java's logical families - {@code Font.MONOSPACED} and
 * {@code Font.SERIF} - so nothing has to be committed or installed. A lab report is usually
 * monospaced columns, and the proportional pass is there because some labs print proportional
 * and OCR finds the two quite different.
 */
public final class OcrCaseRenderer {

	/** The marker a multi-page case's {@code report.txt} splits on, as {@code EvalRunner} uses. */
	static final String PAGE_BREAK = "[PAGE BREAK]";

	/** A real scan is 300 DPI; it is also what Tesseract is told the image is. */
	private static final int SCAN_DPI = 300;

	/** The "low-res" variant: what a cheap scanner or a downscaled phone photo gives. */
	private static final int LOW_DPI = 150;

	/** US Letter, in inches, which is what the synthetic reports are shaped like. */
	private static final double PAGE_WIDTH_INCHES = 8.5;
	private static final double PAGE_HEIGHT_INCHES = 11.0;

	/** 11pt text with 14pt leading, the same as the PDF cases use. */
	private static final double FONT_POINTS = 11;
	private static final double LEADING_POINTS = 14;
	private static final double MARGIN_INCHES = 0.5;

	private OcrCaseRenderer() {
	}

	/** What a case is rendered as. Decides which {@code JobSource} the runner builds. */
	public enum Kind {
		PNG,
		JPEG,
		PDF
	}

	/** The six shapes of input from LLD 10.1. */
	public enum Variant {

		/** Rendered at 300 DPI, black on white. The best case OCR will ever see. */
		CLEAN_SCAN(Kind.PNG),

		/** Rotated, unevenly lit, slightly blurred, saved as a middling JPEG. */
		PHONE_PHOTO(Kind.JPEG),

		/** Rendered at 150 DPI and scaled back up: the detail is gone, the size is not. */
		LOW_RES(Kind.PNG),

		/** Stored rotated a quarter turn, with the EXIF tag that says so. */
		ROTATED_EXIF(Kind.JPEG),

		/** The clean scan wrapped in a PDF with no text layer. */
		SCANNED_PDF(Kind.PDF),

		/** Page one with a real text layer, the rest scanned. */
		MIXED_PDF(Kind.PDF);

		private final Kind kind;

		Variant(Kind kind) {
			this.kind = kind;
		}

		public Kind kind() {
			return kind;
		}

		/** Whether this variant produces OCR text for every page that has text. */
		public boolean isFullyOcr() {
			return this != MIXED_PDF;
		}
	}

	/** Which family the page is set in. Both ship with the JDK. */
	public enum Typeface {
		MONOSPACED(Font.MONOSPACED),
		PROPORTIONAL(Font.SERIF);

		private final String family;

		Typeface(String family) {
			this.family = family;
		}
	}

	/** A rendered case: the bytes, and what they are. */
	public record Rendered(byte[] bytes, Kind kind) {

		/** Sizes only. The bytes are a picture of a report. */
		@Override
		public String toString() {
			return "Rendered[kind=" + kind + ", bytes=" + bytes.length + "]";
		}
	}

	/**
	 * Renders {@code reportText} as {@code variant}.
	 *
	 * @param seed fixed per case, so a variant is reproducible
	 */
	public static Rendered render(String reportText, Variant variant, Typeface typeface,
			long seed) {

		List<List<String>> pages = paginate(reportText);

		return switch (variant) {
			case CLEAN_SCAN -> new Rendered(png(renderPages(pages, typeface, SCAN_DPI).getFirst()
					.image()), Kind.PNG);
			case PHONE_PHOTO -> new Rendered(
					jpeg(phonePhoto(renderPages(pages, typeface, SCAN_DPI).getFirst().image(),
							new Random(seed)), 0.70f), Kind.JPEG);
			case LOW_RES -> new Rendered(
					png(lowRes(renderPages(pages, typeface, LOW_DPI).getFirst().image())), Kind.PNG);
			case ROTATED_EXIF -> new Rendered(
					rotatedWithExif(renderPages(pages, typeface, SCAN_DPI).getFirst().image()),
					Kind.JPEG);
			case SCANNED_PDF -> new Rendered(scannedPdf(pages, typeface), Kind.PDF);
			case MIXED_PDF -> new Rendered(mixedPdf(pages, typeface), Kind.PDF);
		};
	}

	// --- pagination ----------------------------------------------------------

	/**
	 * Splits the text into pages.
	 *
	 * <p>An explicit {@code [PAGE BREAK]} wins, because the case that uses one is testing
	 * exactly that. Otherwise the text is cut at whatever fits a Letter page, so a long report
	 * does not render as one impossibly tall image that no scanner would produce.
	 */
	static List<List<String>> paginate(String reportText) {
		if (reportText.contains(PAGE_BREAK)) {
			return Arrays.stream(reportText.split(java.util.regex.Pattern.quote(PAGE_BREAK)))
					.map(OcrCaseRenderer::linesOf)
					.filter(lines -> !lines.isEmpty())
					.toList();
		}

		List<String> lines = linesOf(reportText);
		int perPage = linesPerPage();
		if (lines.size() <= perPage) {
			return List.of(lines);
		}

		List<List<String>> pages = new ArrayList<>();
		for (int start = 0; start < lines.size(); start += perPage) {
			pages.add(lines.subList(start, Math.min(start + perPage, lines.size())));
		}
		return pages;
	}

	private static int linesPerPage() {
		double usableInches = PAGE_HEIGHT_INCHES - 2 * MARGIN_INCHES;
		return (int) Math.floor(usableInches * 72 / LEADING_POINTS);
	}

	private static List<String> linesOf(String text) {
		List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\R")));
		while (!lines.isEmpty() && lines.getFirst().isBlank()) {
			lines.removeFirst();
		}
		while (!lines.isEmpty() && lines.getLast().isBlank()) {
			lines.removeLast();
		}
		return lines;
	}

	// --- drawing -------------------------------------------------------------

	/** One rendered page and the text it was drawn from. */
	record Page(BufferedImage image, List<String> lines) {

		@Override
		public String toString() {
			return "Page[" + image.getWidth() + "x" + image.getHeight()
					+ ", lines=" + lines.size() + "]";
		}
	}

	private static List<Page> renderPages(List<List<String>> pages, Typeface typeface, int dpi) {
		return pages.stream().map(lines -> new Page(drawPage(lines, typeface, dpi), lines)).toList();
	}

	/**
	 * Draws one page of text, black on white, at {@code dpi}.
	 *
	 * <p>Grayscale from the start rather than colour converted later: that is what a scanner
	 * produces, and it keeps the synthetic input honest about what the preparer will see.
	 */
	private static BufferedImage drawPage(List<String> lines, Typeface typeface, int dpi) {
		int width = (int) Math.round(PAGE_WIDTH_INCHES * dpi);
		int height = (int) Math.round(PAGE_HEIGHT_INCHES * dpi);
		double scale = dpi / 72.0;

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, width, height);

			graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
					RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
					RenderingHints.VALUE_RENDER_QUALITY);
			graphics.setColor(Color.BLACK);
			graphics.setFont(new Font(typeface.family, Font.PLAIN,
					(int) Math.round(FONT_POINTS * scale)));

			int x = (int) Math.round(MARGIN_INCHES * dpi);
			double leading = LEADING_POINTS * scale;
			double y = MARGIN_INCHES * dpi + leading;

			for (String line : lines) {
				graphics.drawString(line, x, (int) Math.round(y));
				y += leading;
			}
		}
		finally {
			graphics.dispose();
		}
		return image;
	}

	// --- variants ------------------------------------------------------------

	/**
	 * A photograph of a page: off-square, unevenly lit, a little out of focus.
	 *
	 * <p>Each step is one real failure of holding a phone over a piece of paper, and together
	 * they are what separates a demo that works from one that works for a user. The rotation is
	 * small on purpose - this is a photo of a report, not a puzzle - and the gradient is the
	 * window light every kitchen table has.
	 */
	private static BufferedImage phonePhoto(BufferedImage page, Random random) {
		double degrees = -3 + random.nextDouble() * 6;
		BufferedImage rotated = rotate(page, degrees);
		BufferedImage lit = applyLightingGradient(rotated, random);
		return blur(lit);
	}

	/** Rotates by a small angle on a white ground, keeping the whole page in frame. */
	private static BufferedImage rotate(BufferedImage source, double degrees) {
		double radians = Math.toRadians(degrees);
		double sin = Math.abs(Math.sin(radians));
		double cos = Math.abs(Math.cos(radians));
		int width = (int) Math.round(source.getWidth() * cos + source.getHeight() * sin);
		int height = (int) Math.round(source.getWidth() * sin + source.getHeight() * cos);

		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D graphics = target.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, width, height);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR);

			AffineTransform transform = new AffineTransform();
			transform.translate(width / 2.0, height / 2.0);
			transform.rotate(radians);
			transform.translate(-source.getWidth() / 2.0, -source.getHeight() / 2.0);
			graphics.drawImage(source, transform, null);
		}
		finally {
			graphics.dispose();
		}
		return target;
	}

	/**
	 * Darkens one side of the page, the way a hand or a lamp does.
	 *
	 * <p><strong>Why the ramp is gentle, and what measuring it taught us.</strong> Measured
	 * 2026-11-01: a ramp down to 55% made six of the ten tuning cases unreadable, and so did one
	 * down to 70%. Running {@code tesseract} by hand on the 70% render explains why - it returns
	 * only the right-hand column, the bright end of the gradient, and loses the entire darker
	 * half of the page. The page is perfectly legible to a person; what fails is Tesseract's
	 * <em>global</em> binarisation, which picks one threshold for the whole image and drops
	 * everything on the dark side of it.
	 *
	 * <p>That is a genuine finding about photographs and belongs in the phase report (it is the
	 * evidence that would justify revisiting O8's "no thresholding in Java"). It is not, however,
	 * what this variant is for: a case that produces no text at all measures nothing about digit
	 * accuracy, and a gate that counts zero dangerous flags because nothing was flagged is a gate
	 * passing for the wrong reason. So the ramp is 85% - a photo taken in ordinary indoor light -
	 * and the binarisation failure is reported separately rather than drowning this variant.
	 */
	private static BufferedImage applyLightingGradient(BufferedImage source, Random random) {
		int width = source.getWidth();
		int height = source.getHeight();
		boolean fromLeft = random.nextBoolean();

		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				double across = (double) (fromLeft ? x : width - 1 - x) / (width - 1);
				double factor = 0.85 + 0.15 * across;
				int gray = source.getRaster().getSample(x, y, 0);
				target.getRaster().setSample(x, y, 0,
						Math.min(255, (int) Math.round(gray * factor)));
			}
		}
		return target;
	}

	/** A 3x3 blur: a phone that focused on the table rather than the page. */
	private static BufferedImage blur(BufferedImage source) {
		float[] weights = {
				1 / 16f, 2 / 16f, 1 / 16f,
				2 / 16f, 4 / 16f, 2 / 16f,
				1 / 16f, 2 / 16f, 1 / 16f };
		BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(),
				BufferedImage.TYPE_BYTE_GRAY);
		new ConvolveOp(new Kernel(3, 3, weights), ConvolveOp.EDGE_NO_OP, null)
				.filter(source, target);
		return target;
	}

	/**
	 * Scales a 150 DPI render up to 300 DPI size.
	 *
	 * <p>The interesting failure: the file looks like a 300 DPI scan and has none of the detail.
	 * Upscaling cannot add back what was never sampled, so this is the variant that tests
	 * whether a value read from mush gets a flag it has not earned.
	 */
	private static BufferedImage lowRes(BufferedImage lowDpiPage) {
		double factor = (double) SCAN_DPI / LOW_DPI;
		int width = (int) Math.round(lowDpiPage.getWidth() * factor);
		int height = (int) Math.round(lowDpiPage.getHeight() * factor);

		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D graphics = target.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(lowDpiPage, 0, 0, width, height, null);
		}
		finally {
			graphics.dispose();
		}
		return target;
	}

	// --- PDFs ----------------------------------------------------------------

	/** Every page as an image, so PDFBox's stripper finds nothing at all. */
	private static byte[] scannedPdf(List<List<String>> pages, Typeface typeface) {
		try (PDDocument document = new PDDocument()) {
			for (Page page : renderPages(pages, typeface, SCAN_DPI)) {
				addImagePage(document, page.image());
			}
			return toBytes(document);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build a scanned PDF", ex);
		}
	}

	/**
	 * Page one with real text, the rest scanned.
	 *
	 * <p>A single-page case still has to produce two pages or there is nothing mixed about it,
	 * so its lines are split in half. That is also the realistic shape: a printed report with a
	 * scanned addendum.
	 */
	private static byte[] mixedPdf(List<List<String>> pages, Typeface typeface) {
		List<List<String>> split = pages.size() >= 2 ? pages : splitInHalf(pages.getFirst());

		try (PDDocument document = new PDDocument()) {
			addTextPage(document, split.getFirst());
			for (Page page : renderPages(split.subList(1, split.size()), typeface, SCAN_DPI)) {
				addImagePage(document, page.image());
			}
			return toBytes(document);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build a mixed PDF", ex);
		}
	}

	private static List<List<String>> splitInHalf(List<String> lines) {
		int half = Math.max(1, lines.size() / 2);
		return List.of(lines.subList(0, half), lines.subList(half, lines.size()));
	}

	/** One page whose whole content is a photograph of the text. */
	private static void addImagePage(PDDocument document, BufferedImage image) throws IOException {
		PDPage page = new PDPage(PDRectangle.LETTER);
		document.addPage(page);

		// JPEG for the big grayscale rasters: a lossless 8.4-megapixel page is about 8 MB, and
		// a ten-page case would then be a 80 MB temp file for no gain in what OCR can read.
		PDImageXObject embedded = image.getType() == BufferedImage.TYPE_BYTE_GRAY
				? JPEGFactory.createFromImage(document, image, 0.9f)
				: LosslessFactory.createFromImage(document, image);

		try (PDPageContentStream content = new PDPageContentStream(document, page)) {
			content.drawImage(embedded, 0, 0, PDRectangle.LETTER.getWidth(),
					PDRectangle.LETTER.getHeight());
		}
	}

	/** One page of real, extractable text, for the mixed case's first page. */
	private static void addTextPage(PDDocument document, List<String> lines) throws IOException {
		PDPage page = new PDPage(PDRectangle.LETTER);
		document.addPage(page);

		PDType1Font font = new PDType1Font(Standard14Fonts.FontName.COURIER);
		try (PDPageContentStream content = new PDPageContentStream(document, page)) {
			content.beginText();
			content.setFont(font, (float) FONT_POINTS);
			content.setLeading((float) LEADING_POINTS);
			content.newLineAtOffset((float) (MARGIN_INCHES * 72),
					PDRectangle.LETTER.getHeight() - (float) (MARGIN_INCHES * 72));
			for (String line : lines) {
				// Standard 14 fonts are WinAnsi; a micro sign or a ≤ in a case would otherwise
				// throw rather than render. Dropping to a close ASCII twin keeps the page
				// honest about what a text layer carries.
				content.showText(toWinAnsi(line));
				content.newLine();
			}
			content.endText();
		}
	}

	/** Replaces the few non-WinAnsi characters the dataset uses. */
	private static String toWinAnsi(String line) {
		return line
				.replace("≤", "<=")
				.replace("≥", ">=")
				.replace("–", "-")
				.replace("—", "-")
				.replace("μ", "u");
	}

	private static byte[] toBytes(PDDocument document) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		document.save(out);
		return out.toByteArray();
	}

	// --- encoding ------------------------------------------------------------

	private static byte[] png(BufferedImage image) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not write a PNG", ex);
		}
	}

	private static byte[] jpeg(BufferedImage image, float quality) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeJpeg(image, quality, null, out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not write a JPEG", ex);
		}
	}

	/**
	 * Writes the page a quarter turn clockwise, with the EXIF tag that says which way is up.
	 *
	 * <p>This is the only variant that tests {@code ImagePreparer}'s EXIF handling end to end,
	 * and the only one where a bug is invisible in the file itself: the bytes are a sideways
	 * page, and whether the text comes back depends entirely on whether the tag was honoured.
	 * Orientation 6 means "rotate 90° clockwise to display", which is what a phone held
	 * upright writes.
	 */
	private static byte[] rotatedWithExif(BufferedImage page) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeJpeg(rotate(page, 270), 0.9f, exifOrientation(6), out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not write a rotated JPEG", ex);
		}
	}

	private static void writeJpeg(BufferedImage image, float quality, byte[] app1,
			ByteArrayOutputStream out) throws IOException {

		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IllegalStateException("no JPEG writer");
		}
		ImageWriter writer = writers.next();

		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(stream);

			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);

			IIOMetadata metadata = null;
			if (app1 != null) {
				metadata = writer.getDefaultImageMetadata(
						ImageTypeSpecifier.createFromRenderedImage(image), param);
				attachApp1(metadata, app1);
			}

			writer.write(null, new IIOImage(image, null, metadata), param);
		}
		finally {
			writer.dispose();
		}
	}

	/**
	 * Hangs an APP1 segment off the JPEG metadata tree.
	 *
	 * <p>The writer exposes anything it does not model itself as an {@code unknown} node under
	 * {@code markerSequence}, which is the same shape {@code ImagePreparer} reads it back out
	 * of - so this writes exactly what production parses, and the round trip is a real test
	 * rather than two independent guesses about the format.
	 */
	private static void attachApp1(IIOMetadata metadata, byte[] app1) throws IOException {
		String format = metadata.getNativeMetadataFormatName();
		IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

		IIOMetadataNode markerSequence = null;
		for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
			if ("markerSequence".equals(child.getNodeName())) {
				markerSequence = (IIOMetadataNode) child;
				break;
			}
		}
		if (markerSequence == null) {
			markerSequence = new IIOMetadataNode("markerSequence");
			root.appendChild(markerSequence);
		}

		IIOMetadataNode unknown = new IIOMetadataNode("unknown");
		unknown.setAttribute("MarkerTag", "225");
		unknown.setUserObject(app1);
		markerSequence.insertBefore(unknown, markerSequence.getFirstChild());

		metadata.setFromTree(format, root);
	}

	/**
	 * The smallest valid EXIF payload carrying one orientation tag.
	 *
	 * <p>{@code "Exif\0\0"}, then a little-endian TIFF header, then a one-entry directory. Hand
	 * built because a library whose only job is this would be a dependency to keep up to date
	 * forever, and because the parser on the other side is also ours - writing both from the
	 * specification keeps them honest about it.
	 */
	static byte[] exifOrientation(int orientation) {
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(6 + 8 + 2 + 12 + 4);
		buffer.put(new byte[] { 'E', 'x', 'i', 'f', 0, 0 });
		buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);

		buffer.put((byte) 'I').put((byte) 'I');   // little-endian marker
		buffer.putShort((short) 42);              // the TIFF magic number
		buffer.putInt(8);                         // offset of the first directory, from here

		buffer.putShort((short) 1);               // one entry
		buffer.putShort((short) 0x0112);          // Orientation
		buffer.putShort((short) 3);               // type SHORT
		buffer.putInt(1);                         // one value
		buffer.putShort((short) orientation);
		buffer.putShort((short) 0);               // the value field is four bytes wide
		buffer.putInt(0);                         // no next directory

		return buffer.array();
	}
}
