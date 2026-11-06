package dev.saq.mediscan.extract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads EXIF orientation with production's own parser, for tests outside this package.
 *
 * <p>Deliberately a bridge rather than a second implementation. The evaluation renders a
 * sideways JPEG and has to prove the orientation tag is in it; checking that with a parser
 * written in test code would let the writer and the test agree with each other while both
 * misread the format, and the real reader - {@link ImagePreparer} - would be the only one that
 * disagreed, at the point where it matters.
 */
public final class ExifProbe {

	private ExifProbe() {
	}

	/** The orientation stored in {@code jpegBytes}, or 1 when there is none. */
	public static int orientationOf(byte[] jpegBytes) throws IOException {
		Path file = Files.createTempFile("exif-probe-", ".jpg");
		try {
			Files.write(file, jpegBytes);
			return ImagePreparer.orientationOf(file);
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	/** The orientation inside a raw EXIF payload, parsed by production code. */
	public static int orientationFromExif(byte[] app1) {
		return ImagePreparer.orientationInExif(app1);
	}
}
