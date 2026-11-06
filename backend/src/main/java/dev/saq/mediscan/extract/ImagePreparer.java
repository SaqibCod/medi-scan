package dev.saq.mediscan.extract;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;

/**
 * Turns an uploaded photo into something Tesseract can read, without letting it eat the heap.
 *
 * <p><strong>The size check comes before any decode, and that ordering is the whole defence.</strong>
 * Width and height are read from the file header through an {@link ImageReader}, which parses
 * the PNG {@code IHDR} or the JPEG {@code SOF} and nothing else. A 33-byte PNG can honestly
 * claim to be 50,000 x 50,000 - 2.5 gigapixels - and decoding it to find out is how a
 * decompression bomb wins. Over {@code ocr.max-pixels}, the file is refused as unreadable and
 * never decoded.
 *
 * <p><strong>Then the decoder itself is bounded.</strong> The pixel cap limits what is
 * <em>accepted</em>; it does not limit what gets <em>allocated</em>, and a 40-megapixel image
 * decoded whole is 40 MB in grayscale or 120 MB as RGB. So the decode is subsampled: the reader
 * is asked for every n-th pixel, with n chosen so the result lands inside the long-edge band
 * Tesseract reads best, and asked to write grayscale directly where it can, so there is no
 * full-size and no colour intermediate at any point. The expected allocation is then checked
 * against {@code ocr.max-decoded-bytes} before {@code read} is called. Worst case under these
 * limits is a 4000 x 4000 grayscale raster, about 16 MB, rather than 120.
 *
 * <p><strong>EXIF rotation, with the JDK alone.</strong> A phone photo is usually stored in the
 * sensor's orientation with a tag saying which way is up; ignoring it hands Tesseract a sideways
 * page. The orientation is read by pulling the APP1 segment out of the JPEG metadata tree and
 * walking the little TIFF structure inside it. No third-party library was needed, which is why
 * this phase adds no dependency.
 *
 * <p>Every file this class writes is deleted by its caller in a {@code finally}. The decoded
 * image is released as soon as the PNG is written.
 */
@Component
class ImagePreparer {

	private static final Logger log = LoggerFactory.getLogger(ImagePreparer.class);

	/** EXIF tag 0x0112. */
	private static final int ORIENTATION_TAG = 0x0112;

	/** The JPEG APP1 marker, as the JDK's metadata tree names it. */
	private static final String APP1_MARKER = "225";

	/** Grayscale is one byte per pixel; a reader that cannot oblige gives us four. */
	private static final int GRAY_BYTES_PER_PIXEL = 1;
	private static final int FALLBACK_BYTES_PER_PIXEL = 4;

	private final long maxPixels;
	private final long maxDecodedBytes;
	private final int minLongEdge;
	private final int maxLongEdge;

	ImagePreparer(MediScanProperties properties) {
		this.maxPixels = properties.ocr().maxPixels();
		this.maxDecodedBytes = properties.ocr().maxDecodedBytes();
		this.minLongEdge = properties.ocr().minLongEdge();
		this.maxLongEdge = properties.ocr().maxLongEdge();
	}

	/**
	 * Reads {@code source} and writes a prepared PNG beside it.
	 *
	 * @param source the uploaded image
	 * @param directory where to write the prepared file
	 * @return the prepared PNG, which the caller must delete
	 * @throws ReportFailure with {@code UNREADABLE} if the image cannot be read, has no
	 *     readable header, or is larger than the limits allow
	 */
	Path prepare(Path source, Path directory) {
		try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
			if (input == null) {
				throw new ReportFailure(ReportErrorCode.UNREADABLE);
			}

			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				// Not an image format the JVM can read. The signature check at upload already
				// said it was a PNG or JPEG, so this means the file is damaged.
				log.info("image rejected: no reader for the file's format");
				throw new ReportFailure(ReportErrorCode.UNREADABLE);
			}

			ImageReader reader = readers.next();
			try {
				// ignoreMetadata = false: the EXIF orientation lives in the metadata, and a
				// reader set up to skip it reports no APP1 segment at all.
				reader.setInput(input, true, false);
				return prepare(reader, source, directory);
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException ex) {
			// Truncated, or a header that does not survive parsing. The message can name the
			// path, so only the exception type is logged.
			log.info("image could not be read: {}", ex.getClass().getSimpleName());
			throw new ReportFailure(ReportErrorCode.UNREADABLE);
		}
	}

	private Path prepare(ImageReader reader, Path source, Path directory) throws IOException {
		int width = reader.getWidth(0);
		int height = reader.getHeight(0);
		long pixels = (long) width * height;

		// Decided from the header. Nothing has been decoded at this point, and nothing will be
		// if this check fails - which is the only safe way to handle a decompression bomb.
		if (pixels > maxPixels) {
			log.info("image rejected: {} pixels over the {} limit", pixels, maxPixels);
			throw new ReportFailure(ReportErrorCode.UNREADABLE);
		}

		int subsampling = subsamplingFor(width, height);
		int decodedWidth = ceilDiv(width, subsampling);
		int decodedHeight = ceilDiv(height, subsampling);

		ImageReadParam param = reader.getDefaultReadParam();
		param.setSourceSubsampling(subsampling, subsampling, 0, 0);
		boolean grayDirect = requestGrayscale(reader, param);

		long expectedBytes = (long) decodedWidth * decodedHeight
				* (grayDirect ? GRAY_BYTES_PER_PIXEL : FALLBACK_BYTES_PER_PIXEL);
		if (expectedBytes > maxDecodedBytes) {
			// The pixel cap bounds what we accept; this bounds what we allocate. Checked
			// before read(), so an image that would not fit is never attempted.
			log.info("image rejected: decoding would allocate {} bytes, over the {} limit",
					expectedBytes, maxDecodedBytes);
			throw new ReportFailure(ReportErrorCode.UNREADABLE);
		}

		int orientation = orientationOf(reader);

		BufferedImage image = reader.read(0, param);
		try {
			BufferedImage prepared = scale(rotate(toGrayscale(image), orientation));
			Path target = Files.createTempFile(directory, "ocr-", ".png");
			try {
				ImageIO.write(prepared, "png", target.toFile());
			}
			finally {
				// Released as soon as the bytes are on disk. On a 2 GB box the window where
				// both the raster and the encoded PNG exist is the peak, and it ends here.
				if (prepared != image) {
					prepared.flush();
				}
			}

			log.info("image prepared: source={}x{} subsampling={} decoded={}x{} "
					+ "prepared={}x{} grayDirect={} orientation={}",
					width, height, subsampling, decodedWidth, decodedHeight,
					prepared.getWidth(), prepared.getHeight(), grayDirect, orientation);

			return target;
		}
		finally {
			image.flush();
		}
	}

	/**
	 * How much to subsample so the decoded image lands inside the long-edge band.
	 *
	 * <p>The smallest step that brings the long edge to {@code maxLongEdge} or below, so the
	 * decoder allocates as little as it can while still giving Tesseract enough to read. Backed
	 * off if it would take the image below {@code minLongEdge}, because too small is a worse
	 * problem than slightly large: Tesseract reads small text badly.
	 */
	private int subsamplingFor(int width, int height) {
		int longEdge = Math.max(width, height);
		if (longEdge <= maxLongEdge) {
			return 1;
		}

		int step = ceilDiv(longEdge, maxLongEdge);
		while (step > 1 && longEdge / step < minLongEdge) {
			step--;
		}
		return step;
	}

	/**
	 * Asks the reader to produce grayscale directly, if it offers it.
	 *
	 * <p>Both the PNG and JPEG readers do. It matters because the alternative is decoding to
	 * colour and converting, which allocates the colour raster first - three or four times the
	 * grayscale one, for an image we are about to throw the colour away from.
	 */
	private static boolean requestGrayscale(ImageReader reader, ImageReadParam param)
			throws IOException {

		for (Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0); types.hasNext();) {
			ImageTypeSpecifier type = types.next();
			if (type.getBufferedImageType() == BufferedImage.TYPE_BYTE_GRAY) {
				param.setDestinationType(type);
				return true;
			}
		}
		return false;
	}

	/** Converts to 8-bit grayscale if the decode did not already produce it. */
	private static BufferedImage toGrayscale(BufferedImage image) {
		if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
			return image;
		}

		BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(),
				BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D graphics = gray.createGraphics();
		try {
			graphics.drawImage(image, 0, 0, null);
		}
		finally {
			graphics.dispose();
		}
		return gray;
	}

	/**
	 * Applies an EXIF orientation.
	 *
	 * <p>Only the four orientations a camera actually produces are handled as rotations; the
	 * mirrored ones (2, 4, 5, 7) are rare enough on a photographed document that treating them
	 * as upright is better than guessing wrong, and Tesseract's own orientation detection can
	 * cope with a mirrored page better than with an upside-down one.
	 */
	private static BufferedImage rotate(BufferedImage image, int orientation) {
		int degrees = switch (orientation) {
			case 3 -> 180;
			case 6 -> 90;
			case 8 -> 270;
			default -> 0;
		};
		if (degrees == 0) {
			return image;
		}

		boolean quarterTurn = degrees == 90 || degrees == 270;
		int width = quarterTurn ? image.getHeight() : image.getWidth();
		int height = quarterTurn ? image.getWidth() : image.getHeight();

		BufferedImage rotated = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D graphics = rotated.createGraphics();
		try {
			AffineTransform transform = new AffineTransform();
			transform.translate(width / 2.0, height / 2.0);
			transform.rotate(Math.toRadians(degrees));
			transform.translate(-image.getWidth() / 2.0, -image.getHeight() / 2.0);
			graphics.drawImage(image, transform, null);
		}
		finally {
			graphics.dispose();
		}

		image.flush();
		return rotated;
	}

	/** Scales the long edge into the band Tesseract reads best. */
	private BufferedImage scale(BufferedImage image) {
		int longEdge = Math.max(image.getWidth(), image.getHeight());
		double factor = 1;
		if (longEdge > maxLongEdge) {
			factor = (double) maxLongEdge / longEdge;
		}
		else if (longEdge < minLongEdge) {
			factor = (double) minLongEdge / longEdge;
		}
		if (factor == 1) {
			return image;
		}

		int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
		int height = Math.max(1, (int) Math.round(image.getHeight() * factor));

		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D graphics = scaled.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
					RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(image, 0, 0, width, height, null);
		}
		finally {
			graphics.dispose();
		}

		image.flush();
		return scaled;
	}

	// --- EXIF ----------------------------------------------------------------

	/** The EXIF orientation, or 1 (upright) when there is none or it cannot be read. */
	private static int orientationOf(ImageReader reader) {
		try {
			IIOMetadata metadata = reader.getImageMetadata(0);
			if (metadata == null) {
				return 1;
			}
			String format = metadata.getNativeMetadataFormatName();
			if (format == null || !format.startsWith("javax_imageio_jpeg")) {
				// PNGs carry no EXIF orientation worth honouring.
				return 1;
			}

			byte[] app1 = findApp1(metadata.getAsTree(format));
			if (app1 == null) {
				return 1;
			}
			int orientation = orientationFromExif(app1);
			return orientation >= 1 && orientation <= 8 ? orientation : 1;
		}
		catch (IOException | RuntimeException ex) {
			// A malformed EXIF block is not a reason to refuse a readable photo. Upright is
			// the safe assumption, and Tesseract can often cope.
			return 1;
		}
	}

	/** The APP1 segment's bytes, which the JDK exposes as an "unknown" marker node. */
	private static byte[] findApp1(Node node) {
		if ("unknown".equals(node.getNodeName()) && node instanceof IIOMetadataNode metadata) {
			Node tag = node.getAttributes().getNamedItem("MarkerTag");
			if (tag != null && APP1_MARKER.equals(tag.getNodeValue())
					&& metadata.getUserObject() instanceof byte[] bytes) {
				return bytes;
			}
		}
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
			byte[] found = findApp1(child);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/**
	 * Walks the TIFF structure inside an EXIF payload for the orientation tag.
	 *
	 * <p>The payload is {@code "Exif\0\0"} followed by a complete little TIFF file: a byte-order
	 * mark, the magic number 42, the offset of the first directory, then a count and that many
	 * 12-byte entries. Offsets inside it are relative to the TIFF header, not the segment, which
	 * is why the buffer is sliced past the prefix rather than merely positioned - a buffer
	 * wrapped with an offset keeps array-absolute positions and every offset would be wrong.
	 */
	private static int orientationFromExif(byte[] app1) {
		int base = 0;
		if (app1.length > 6 && app1[0] == 'E' && app1[1] == 'x' && app1[2] == 'i'
				&& app1[3] == 'f') {
			base = 6;
		}
		if (app1.length < base + 8) {
			return 1;
		}

		ByteOrder order = app1[base] == 'M' ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
		ByteBuffer tiff = ByteBuffer.wrap(app1, base, app1.length - base).slice().order(order);

		tiff.position(4);
		int directory = tiff.getInt();
		if (directory < 8 || directory + 2 > tiff.limit()) {
			return 1;
		}

		tiff.position(directory);
		int entries = tiff.getShort() & 0xFFFF;
		for (int i = 0; i < entries; i++) {
			int entry = directory + 2 + i * 12;
			if (entry + 12 > tiff.limit()) {
				return 1;
			}
			tiff.position(entry);
			int tag = tiff.getShort() & 0xFFFF;
			tiff.getShort();
			tiff.getInt();
			int value = tiff.getShort() & 0xFFFF;
			if (tag == ORIENTATION_TAG) {
				return value;
			}
		}
		return 1;
	}

	private static int ceilDiv(int value, int divisor) {
		return (value + divisor - 1) / divisor;
	}

	/**
	 * Reads a file's EXIF orientation without decoding it. Exposed for tests.
	 *
	 * <p>The evaluation renders a sideways JPEG with an orientation tag and needs to assert the
	 * tag is really there - against this parser, not a second one written in test code, or the
	 * two could agree with each other and both be wrong about the format.
	 */
	static int orientationOf(Path file) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
			if (input == null) {
				return 1;
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				return 1;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, false);
				return orientationOf(reader);
			}
			finally {
				reader.dispose();
			}
		}
	}

	/** Reads the orientation out of a raw EXIF payload. Exposed for the same test. */
	static int orientationInExif(byte[] app1) {
		return orientationFromExif(app1);
	}

	/** Reads an image's header size without decoding it. Exposed for the bomb test. */
	static int[] headerSize(Path file) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
			if (input == null) {
				return null;
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				return new int[] { reader.getWidth(0), reader.getHeight(0) };
			}
			finally {
				reader.dispose();
			}
		}
	}

}
