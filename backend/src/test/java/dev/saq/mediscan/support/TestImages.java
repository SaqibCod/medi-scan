package dev.saq.mediscan.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/**
 * Synthetic images for the OCR tests.
 *
 * <p>Generated rather than committed, like the PDFs in {@link TestPdfs}: a binary fixture in
 * git is a thing nobody can review, and these need to be built to order anyway - a specific
 * EXIF orientation, a specific declared size, a header with no pixels behind it.
 */
public final class TestImages {

	private TestImages() {
	}

	/** A grayscale PNG with some drawn content, so scaling has something to preserve. */
	public static Path writePng(Path directory, int width, int height) throws IOException {
		Path file = directory.resolve("image-" + width + "x" + height + ".png");
		ImageIO.write(content(width, height, BufferedImage.TYPE_BYTE_GRAY), "png", file.toFile());
		return file;
	}

	/** A full-colour PNG, for asserting the preparer converts to grayscale. */
	public static Path writeColourPng(Path directory, int width, int height) throws IOException {
		Path file = directory.resolve("colour-" + width + "x" + height + ".png");
		ImageIO.write(content(width, height, BufferedImage.TYPE_3BYTE_BGR), "png", file.toFile());
		return file;
	}

	/**
	 * A PNG header claiming a size, with <strong>no pixel data at all</strong>.
	 *
	 * <p>33 bytes that announce themselves as however many megapixels you ask for. This is the
	 * shape of a decompression bomb, reduced to the part that matters: any code that reads the
	 * size from the header refuses it instantly, and any code that decodes first dies. Because
	 * the pixels genuinely are not there, a test that passes with this file has proved the
	 * header path rather than measured it.
	 */
	public static byte[] pngHeaderClaiming(int width, int height) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });

		byte[] ihdr = new byte[17];
		ihdr[0] = 'I';
		ihdr[1] = 'H';
		ihdr[2] = 'D';
		ihdr[3] = 'R';
		writeInt(ihdr, 4, width);
		writeInt(ihdr, 8, height);
		ihdr[12] = 8;                                       // bit depth
		ihdr[13] = 0;                                       // grayscale
		ihdr[14] = 0;                                       // deflate
		ihdr[15] = 0;                                       // no filter
		ihdr[16] = 0;                                       // no interlace

		out.writeBytes(new byte[] { 0, 0, 0, 13 });
		out.writeBytes(ihdr);

		CRC32 crc = new CRC32();
		crc.update(ihdr, 0, ihdr.length);
		writeIntTo(out, (int) crc.getValue());

		return out.toByteArray();
	}

	/** A JPEG with no EXIF segment. */
	public static byte[] plainJpeg(int width, int height) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(content(width, height, BufferedImage.TYPE_3BYTE_BGR), "jpeg", out);
		return out.toByteArray();
	}

	/**
	 * A JPEG carrying an EXIF orientation tag.
	 *
	 * <p>The APP1 segment goes <strong>after</strong> any JFIF APP0, not before it: the JDK's
	 * JPEG reader rejects the whole file with "JFIF APP0 must be first marker after SOI"
	 * otherwise, which looks exactly like a broken EXIF parser and is not.
	 */
	public static byte[] jpegWithOrientation(int width, int height, int orientation)
			throws IOException {

		return spliceApp1(plainJpeg(width, height), exifPayload(orientation));
	}

	/** A JPEG whose EXIF segment is structurally nonsense. */
	public static byte[] jpegWithCorruptExif(int width, int height) throws IOException {
		byte[] corrupt = { 'E', 'x', 'i', 'f', 0, 0, 'Z', 'Z', 99, 99, 0, 0, 0, 0, 0, 0 };
		return spliceApp1(plainJpeg(width, height), corrupt);
	}

	private static byte[] spliceApp1(byte[] jpeg, byte[] payload) {
		int insertAt = 2;
		if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
			int app0Length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
			insertAt = 4 + app0Length;
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, insertAt);
		out.write(0xFF);
		out.write(0xE1);
		int length = payload.length + 2;
		out.write((length >> 8) & 0xFF);
		out.write(length & 0xFF);
		out.writeBytes(payload);
		out.write(jpeg, insertAt, jpeg.length - insertAt);
		return out.toByteArray();
	}

	/** "Exif\0\0" then a big-endian TIFF header with one entry: the orientation. */
	private static byte[] exifPayload(int orientation) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] { 'E', 'x', 'i', 'f', 0, 0 });
		out.writeBytes(new byte[] { 'M', 'M', 0, 42 });      // big endian, magic 42
		out.writeBytes(new byte[] { 0, 0, 0, 8 });           // first directory at offset 8
		out.writeBytes(new byte[] { 0, 1 });                 // one entry
		out.writeBytes(new byte[] { 0x01, 0x12 });           // tag 0x0112, Orientation
		out.writeBytes(new byte[] { 0, 3 });                 // type SHORT
		out.writeBytes(new byte[] { 0, 0, 0, 1 });           // count 1
		// A SHORT is left-aligned in its four-byte value field.
		out.writeBytes(new byte[] { (byte) ((orientation >> 8) & 0xFF),
				(byte) (orientation & 0xFF), 0, 0 });
		out.writeBytes(new byte[] { 0, 0, 0, 0 });           // no next directory
		return out.toByteArray();
	}

	/** Something with edges and text, so a scaled or rotated result is not uniformly blank. */
	private static BufferedImage content(int width, int height, int type) {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, width, height);
			graphics.setColor(Color.BLACK);
			graphics.drawString("TOP LEFT", 10, 20);
			graphics.drawRect(4, 4, Math.max(1, width - 9), Math.max(1, height - 9));
		}
		finally {
			graphics.dispose();
		}
		return image;
	}

	private static void writeInt(byte[] target, int offset, int value) {
		target[offset] = (byte) (value >>> 24);
		target[offset + 1] = (byte) (value >>> 16);
		target[offset + 2] = (byte) (value >>> 8);
		target[offset + 3] = (byte) value;
	}

	private static void writeIntTo(ByteArrayOutputStream out, int value) {
		out.write((value >>> 24) & 0xFF);
		out.write((value >>> 16) & 0xFF);
		out.write((value >>> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	/** Writes bytes to a file, for the fixtures built as byte arrays. */
	public static Path write(Path directory, String name, byte[] bytes) throws IOException {
		Path file = directory.resolve(name);
		Files.write(file, bytes);
		return file;
	}
}
