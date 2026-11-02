package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.support.TestImages;
import dev.saq.mediscan.support.TestProperties;

/**
 * {@link ImagePreparer}: the limits that keep a photo from eating a 2 GB box, and the rotation
 * that keeps a phone picture the right way up.
 *
 * <p>The decompression-bomb test is the one worth reading. It uses a 33-byte PNG whose header
 * claims 50,000 x 50,000 pixels - a file that <em>cannot</em> be decoded, because the pixel
 * data is not there. So a run that rejects it has provably done so from the header alone,
 * which is a stronger statement than measuring heap after the fact and a good deal more
 * deterministic than running a forked JVM with a small {@code -Xmx} (approved deviation D11).
 */
class ImagePreparerTest {

	@TempDir
	Path directory;

	private static ImagePreparer preparer() {
		return new ImagePreparer(TestProperties.defaults());
	}

	private static ImagePreparer preparer(long maxPixels, long maxDecodedBytes,
			int minLongEdge, int maxLongEdge) {

		return new ImagePreparer(TestProperties.withOcrImageLimits(maxPixels, maxDecodedBytes,
				minLongEdge, maxLongEdge));
	}

	// --- the size check, before any decode ------------------------------------

	@Test
	@DisplayName("a file claiming 2.5 gigapixels is refused from its header, never decoded")
	void decompressionBombRejectedFromHeader() throws IOException {
		// 33 bytes: a PNG signature and an IHDR claiming 50,000 x 50,000, and nothing else.
		// There is no pixel data to decode, so if this returns rather than throwing an
		// out-of-memory error or an IOException from the decoder, the rejection came from the
		// header - which is exactly the claim being made.
		Path bomb = directory.resolve("bomb.png");
		Files.write(bomb, TestImages.pngHeaderClaiming(50_000, 50_000));

		assertThat(Files.size(bomb)).isLessThan(100);
		assertThat(ImagePreparer.headerSize(bomb)).containsExactly(50_000, 50_000);

		assertThatThrownBy(() -> preparer().prepare(bomb, directory))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);

		assertThat(preparedFiles()).isEmpty();
	}

	@Test
	@DisplayName("an image one pixel over the cap is refused")
	void overPixelCapRejected() throws IOException {
		Path image = TestImages.writePng(directory, 100, 100);

		// A cap of 9,999 against a 10,000-pixel image.
		assertThatThrownBy(() -> preparer(9_999, 67_108_864L, 50, 4000).prepare(image, directory))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("an image exactly at the cap is accepted")
	void atPixelCapAccepted() throws IOException {
		Path image = TestImages.writePng(directory, 100, 100);

		Path prepared = preparer(10_000, 67_108_864L, 50, 4000).prepare(image, directory);

		assertThat(prepared).exists();
	}

	@Test
	@DisplayName("a file that is not a readable image is refused")
	void unreadableFileRejected() throws IOException {
		Path notAnImage = directory.resolve("broken.png");
		Files.write(notAnImage, "this is not a png".getBytes());

		assertThatThrownBy(() -> preparer().prepare(notAnImage, directory))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	// --- the decoder budget ---------------------------------------------------

	@Test
	@DisplayName("a decode that would allocate more than the budget is refused before it runs")
	void overDecodedByteBudgetRejected() throws IOException {
		Path image = TestImages.writePng(directory, 2000, 2000);

		// Pixels are allowed, but the resulting raster is not. The two caps answer different
		// questions: what may be accepted, and what may be allocated.
		assertThatThrownBy(() -> preparer(40_000_000L, 1024, 50, 4000).prepare(image, directory))
				.isInstanceOf(ReportFailure.class)
				.extracting(failure -> ((ReportFailure) failure).code())
				.isEqualTo(ReportErrorCode.UNREADABLE);
	}

	@Test
	@DisplayName("a large image is subsampled at decode time, never materialised full size")
	void largeImageIsSubsampled() throws IOException {
		// 6000 x 400. With a 1500-pixel long edge, the decoder is asked for every 4th pixel,
		// so the raster it allocates is a quarter of each dimension - not 6000 x 400 scaled
		// down afterwards.
		Path image = TestImages.writePng(directory, 6000, 400);

		Path prepared = preparer(40_000_000L, 67_108_864L, 300, 1500).prepare(image, directory);

		int[] size = ImagePreparer.headerSize(prepared);
		assertThat(size[0]).isLessThanOrEqualTo(1500);
		// A budget that only a subsampled decode can fit under: the full 6000 x 400 grayscale
		// raster is 2.4 MB, and this allows 1 MB.
		Path again = preparer(40_000_000L, 1_000_000L, 300, 1500).prepare(image, directory);
		assertThat(again).exists();
	}

	@ParameterizedTest(name = "{0}x{1} with a {2}-{3} band becomes {4} on the long edge")
	@CsvSource({
			// Already inside the band: untouched.
			"2000, 1000, 1500, 4000, 2000",
			// Above the band: scaled down to the top of it.
			"8000, 1000, 1500, 4000, 4000",
			// Below the band: scaled up, because Tesseract reads small text badly.
			"800, 400, 1500, 4000, 1500",
	})
	@DisplayName("the long edge is brought into the configured band")
	void scalesIntoTheBand(int width, int height, int minLongEdge, int maxLongEdge,
			int expectedLongEdge) throws IOException {

		Path image = TestImages.writePng(directory, width, height);

		Path prepared = preparer(40_000_000L, 67_108_864L, minLongEdge, maxLongEdge)
				.prepare(image, directory);

		int[] size = ImagePreparer.headerSize(prepared);
		assertThat(Math.max(size[0], size[1]))
				.isCloseTo(expectedLongEdge, org.assertj.core.data.Offset.offset(2));
	}

	@Test
	@DisplayName("the prepared file is an 8-bit grayscale PNG")
	void outputIsGrayscalePng() throws IOException {
		Path image = TestImages.writeColourPng(directory, 1600, 900);

		Path prepared = preparer().prepare(image, directory);

		BufferedImage read = ImageIO.read(prepared.toFile());
		assertThat(read.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
	}

	// --- EXIF -----------------------------------------------------------------

	@ParameterizedTest(name = "EXIF orientation {0} gives a {1}x{2} image")
	@CsvSource({
			// Upright and upside-down keep the shape; the quarter turns swap it.
			"1, 1600, 800",
			"3, 1600, 800",
			"6, 800, 1600",
			"8, 800, 1600",
	})
	@DisplayName("a photo's EXIF orientation is applied")
	void appliesExifOrientation(int orientation, int expectedWidth, int expectedHeight)
			throws IOException {

		Path photo = directory.resolve("photo-" + orientation + ".jpg");
		Files.write(photo, TestImages.jpegWithOrientation(1600, 800, orientation));

		Path prepared = preparer(40_000_000L, 67_108_864L, 100, 4000).prepare(photo, directory);

		int[] size = ImagePreparer.headerSize(prepared);
		assertThat(size).containsExactly(expectedWidth, expectedHeight);
	}

	@Test
	@DisplayName("a JPEG with no EXIF block is treated as upright")
	void noExifIsUpright() throws IOException {
		Path photo = directory.resolve("plain.jpg");
		Files.write(photo, TestImages.plainJpeg(1600, 800));

		Path prepared = preparer(40_000_000L, 67_108_864L, 100, 4000).prepare(photo, directory);

		assertThat(ImagePreparer.headerSize(prepared)).containsExactly(1600, 800);
	}

	@Test
	@DisplayName("a corrupt EXIF block does not cost a readable photo")
	void corruptExifIsTolerated() throws IOException {
		Path photo = directory.resolve("corrupt-exif.jpg");
		Files.write(photo, TestImages.jpegWithCorruptExif(1600, 800));

		Path prepared = preparer(40_000_000L, 67_108_864L, 100, 4000).prepare(photo, directory);

		// Upright is the safe assumption; refusing the photo would not be.
		assertThat(ImagePreparer.headerSize(prepared)).containsExactly(1600, 800);
	}

	// --- temp files -----------------------------------------------------------

	@Test
	@DisplayName("a successful run leaves exactly one prepared file, for the caller to delete")
	void leavesOnePreparedFile() throws IOException {
		Path image = TestImages.writePng(directory, 1600, 900);

		Path prepared = preparer().prepare(image, directory);

		assertThat(preparedFiles()).containsExactly(prepared);
	}

	@Test
	@DisplayName("a rejected image leaves nothing behind")
	void rejectedImageLeavesNothing() throws IOException {
		Path bomb = directory.resolve("bomb.png");
		Files.write(bomb, TestImages.pngHeaderClaiming(50_000, 50_000));

		assertThatThrownBy(() -> preparer().prepare(bomb, directory))
				.isInstanceOf(ReportFailure.class);

		assertThat(preparedFiles()).isEmpty();
	}

	/** The files this class wrote, as opposed to the fixtures the test wrote. */
	private List<Path> preparedFiles() throws IOException {
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(file -> file.getFileName().toString().startsWith("ocr-")).toList();
		}
	}

}
