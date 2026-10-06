package dev.saq.mediscan.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.support.TestProperties;

/**
 * Temp file handling ({@code LLD} 6.3).
 *
 * <p>An uploaded file is the only unmasked copy of someone's medical report, and it lives on
 * disk. Most of these tests are about it not being there any more.
 */
class TempFileStoreTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("streams an upload to a file with a random name")
	void writesUpload() throws IOException {
		TempFileStore store = store();
		byte[] content = "%PDF-1.7 some report bytes".getBytes();

		Path file = store.write(new ByteArrayInputStream(content), 1024);

		assertThat(file).exists().hasBinaryContent(content);
		assertThat(file.getParent()).isEqualTo(store.directory());
		// The original filename is never used: lab PDFs are routinely named after the
		// patient, so carrying it would undo the masking that happens later.
		assertThat(file.getFileName().toString()).endsWith(".tmp").doesNotContain("report");
	}

	@Test
	@DisplayName("refuses an upload over the byte limit")
	void refusesOversizedUpload() {
		TempFileStore store = store();
		byte[] content = new byte[2048];

		assertThatThrownBy(() -> store.write(new ByteArrayInputStream(content), 1024))
				.isInstanceOf(UploadTooLargeException.class);
	}

	@Test
	@DisplayName("leaves nothing behind when it refuses an oversized upload")
	void deletesPartialFileOnOverflow() throws IOException {
		TempFileStore store = store();

		assertThatThrownBy(() -> store.write(new ByteArrayInputStream(new byte[2048]), 1024))
				.isInstanceOf(UploadTooLargeException.class);

		// The partial write is the point: without the cleanup, every rejected upload would
		// leave a file holding the first 1 KB of someone's report.
		assertThat(listTempFiles(store)).isEmpty();
	}

	@Test
	@DisplayName("leaves nothing behind when the stream fails mid-read")
	void deletesPartialFileOnReadFailure() throws IOException {
		TempFileStore store = store();

		InputStream failing = new InputStream() {
			private int reads;

			@Override
			public int read() throws IOException {
				if (reads++ > 4) {
					throw new IOException("connection reset");
				}
				return 'x';
			}
		};

		assertThatThrownBy(() -> store.write(failing, 1024)).isInstanceOf(IOException.class);
		assertThat(listTempFiles(store)).isEmpty();
	}

	@Test
	@DisplayName("stops reading as soon as the limit is passed")
	void stopsReadingAtTheLimit() {
		TempFileStore store = store();
		CountingStream counting = new CountingStream(1_000_000);

		assertThatThrownBy(() -> store.write(counting, 1024))
				.isInstanceOf(UploadTooLargeException.class);

		// There is no reason to drain a request that is already rejected. A little over the
		// limit is the buffer size; anywhere near the full million would mean it kept going.
		assertThat(counting.bytesRead()).isLessThan(1024 + 8192 * 2);
	}

	@Test
	@DisplayName("deleteQuietly removes a file and tolerates one that is already gone")
	void deleteQuietlyIsForgiving() throws IOException {
		TempFileStore store = store();
		Path file = store.create();

		store.deleteQuietly(file);
		assertThat(file).doesNotExist();

		// Called from finally blocks, where throwing would mask the exception being handled.
		store.deleteQuietly(file);
		store.deleteQuietly((Path) null);
	}

	@Test
	@DisplayName("deleteQuietly on a job source removes the PDF behind it")
	void deletesByJobSource() throws IOException {
		TempFileStore store = store();
		Path file = store.create();

		store.deleteQuietly(new JobSource.PdfFile(file));
		assertThat(file).doesNotExist();

		// Sources with no file must be a no-op, not a failure.
		store.deleteQuietly(new JobSource.RawText("pasted"));
		store.deleteQuietly(new JobSource.Sample("cbc", "sample text"));
	}

	@Test
	@DisplayName("the sweep deletes abandoned files and spares fresh ones")
	void sweepsStaleFilesOnly() throws IOException {
		Instant now = Instant.parse("2026-10-06T12:00:00Z");
		TempFileStore store = new TempFileStore(
				TestProperties.withTempDir(tempDir.toString(), Duration.ofMinutes(10)),
				Clock.fixed(now, ZoneOffset.UTC));

		Path stale = store.create();
		Files.setLastModifiedTime(stale,
				java.nio.file.attribute.FileTime.from(now.minus(Duration.ofMinutes(30))));

		Path fresh = store.create();
		Files.setLastModifiedTime(fresh,
				java.nio.file.attribute.FileTime.from(now.minus(Duration.ofMinutes(2))));

		assertThat(store.sweepStaleFiles()).isEqualTo(1);

		assertThat(stale).doesNotExist();
		// The important half: the sweep must not delete a file a running job is still using.
		assertThat(fresh).exists();
	}

	@Test
	@DisplayName("the sweep is safe to run on an empty directory")
	void sweepHandlesEmptyDirectory() {
		assertThat(store().sweepStaleFiles()).isZero();
	}

	@Test
	@DisplayName("creates its directory at startup")
	void createsDirectory() {
		Path nested = tempDir.resolve("does/not/exist/yet");

		TempFileStore store = new TempFileStore(
				TestProperties.withTempDir(nested.toString(), Duration.ofMinutes(10)),
				Clock.systemUTC());

		assertThat(store.directory()).exists().isDirectory();
	}

	@Test
	@DisplayName("falls back to the system temp directory when none is configured")
	void defaultsToSystemTempDirectory() {
		TempFileStore store = new TempFileStore(
				TestProperties.withTempDir("  ", Duration.ofMinutes(10)), Clock.systemUTC());

		assertThat(store.directory()).exists();
		assertThat(store.directory().getFileName().toString()).isEqualTo("medi-scan-uploads");
	}

	private TempFileStore store() {
		return new TempFileStore(
				TestProperties.withTempDir(tempDir.toString(), Duration.ofMinutes(10)),
				Clock.systemUTC());
	}

	private static List<Path> listTempFiles(TempFileStore store) throws IOException {
		try (var files = Files.list(store.directory())) {
			return files.toList();
		}
	}

	/** Reports how much of itself was actually consumed. */
	private static final class CountingStream extends InputStream {

		private final int size;
		private int read;

		CountingStream(int size) {
			this.size = size;
		}

		@Override
		public int read() {
			return read++ < size ? 'x' : -1;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) {
			if (read >= size) {
				return -1;
			}
			int count = Math.min(length, size - read);
			java.util.Arrays.fill(buffer, offset, offset + count, (byte) 'x');
			read += count;
			return count;
		}

		int bytesRead() {
			return read;
		}
	}
}
