package dev.saq.mediscan.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.extract.JobSource;

/**
 * Creates, fills and deletes the temp files uploads are streamed into.
 *
 * <p>An uploaded file is the most sensitive thing this system touches: it is the only
 * unmasked, unredacted copy of someone's medical report, and it lives on disk. So it is
 * written to a directory this class owns, under a random name, with owner-only permissions,
 * and it is deleted in a {@code finally} block as soon as text has been extracted
 * ({@code CLAUDE.md} rule 2).
 *
 * <p>The original filename is never used, stored or logged. Lab PDFs are routinely named
 * after the patient, so carrying the name would defeat the masking that happens later.
 */
@Component
public class TempFileStore {

	private static final Logger log = LoggerFactory.getLogger(TempFileStore.class);

	private static final String FILE_SUFFIX = ".tmp";

	/** {@code rwx------}: nothing else on the box has any business reading these. */
	private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
			PosixFilePermissions.fromString("rwx------");

	private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
			PosixFilePermissions.fromString("rw-------");

	private final Path directory;
	private final Duration maxAge;
	private final Clock clock;

	public TempFileStore(MediScanProperties properties, Clock clock) {
		this.directory = resolveDirectory(properties.upload().tempDir());
		this.maxAge = properties.upload().tempFileMaxAge();
		this.clock = clock;
		createDirectory();
	}

	/** The directory uploads are written to. Exposed for the retention sweep and tests. */
	public Path directory() {
		return directory;
	}

	/**
	 * Streams {@code in} into a new temp file, refusing to write more than {@code maxBytes}.
	 *
	 * <p>The byte count is enforced here as well as by Spring's multipart limit, because the
	 * multipart limit relies on a {@code Content-Length} the client controls. Counting while
	 * writing is the check that cannot be talked out of.
	 *
	 * <p>On any failure - including the size limit - the partial file is deleted before the
	 * exception leaves this method, so a rejected upload leaves nothing behind.
	 *
	 * @return the path written, which the caller now owns and must delete
	 * @throws UploadTooLargeException when {@code in} holds more than {@code maxBytes}
	 */
	public Path write(InputStream in, long maxBytes) throws IOException {
		Path file = create();
		long written = 0;
		try (OutputStream out = Files.newOutputStream(file)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) {
				written += read;
				if (written > maxBytes) {
					// Stop reading immediately rather than draining the request: there is no
					// reason to spend disk and time on bytes that are already rejected.
					throw new UploadTooLargeException(maxBytes);
				}
				out.write(buffer, 0, read);
			}
		}
		catch (IOException | UploadTooLargeException ex) {
			deleteQuietly(file);
			throw ex;
		}
		return file;
	}

	/** A new, empty temp file with a random name and owner-only permissions. */
	public Path create() {
		Path file = directory.resolve(UUID.randomUUID() + FILE_SUFFIX);
		try {
			Files.createFile(file);
			restrictPermissions(file);
			return file;
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not create an upload temp file", ex);
		}
	}

	/**
	 * Deletes a temp file, swallowing any failure.
	 *
	 * <p>Called from {@code finally} blocks, where throwing would mask the exception that is
	 * actually being handled. A file that could not be deleted is picked up by the retention
	 * sweep, so the worst case is a delay rather than a leak.
	 */
	public void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			// No path in the message: it contains nothing sensitive, but the exception class
			// is all that is actionable anyway.
			log.warn("Could not delete an upload temp file: {}", ex.getClass().getSimpleName());
		}
	}

	/** Deletes the temp file behind a job source, if it has one. */
	public void deleteQuietly(JobSource source) {
		if (source instanceof JobSource.PdfFile(Path path)) {
			deleteQuietly(path);
		}
	}

	/**
	 * Deletes temp files older than {@code mediscan.upload.temp-file-max-age}.
	 *
	 * <p>A safety net, not the mechanism: jobs delete their own file. This catches the file
	 * left behind when the JVM was killed between writing a file and finishing its job, which
	 * no {@code finally} block can cover.
	 *
	 * @return how many files were removed
	 */
	public int sweepStaleFiles() {
		Instant cutoff = Instant.now(clock).minus(maxAge);
		int deleted = 0;

		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
			for (Path file : files) {
				try {
					if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
						Files.deleteIfExists(file);
						deleted++;
					}
				}
				catch (IOException ex) {
					// A file being deleted by its own job while we look at it is normal.
					log.debug("Skipped a temp file during sweep: {}", ex.getClass().getSimpleName());
				}
			}
		}
		catch (IOException ex) {
			log.warn("Temp file sweep failed: {}", ex.getClass().getSimpleName());
		}

		if (deleted > 0) {
			log.info("Retention: deleted {} stale upload temp files", deleted);
		}
		return deleted;
	}

	private static Path resolveDirectory(String configured) {
		if (configured != null && !configured.isBlank()) {
			return Paths.get(configured).toAbsolutePath().normalize();
		}
		return Paths.get(System.getProperty("java.io.tmpdir"), "medi-scan-uploads")
				.toAbsolutePath().normalize();
	}

	private void createDirectory() {
		try {
			Files.createDirectories(directory);
			restrictPermissions(directory, OWNER_ONLY_DIR);
			log.info("Upload temp directory ready");
		}
		catch (IOException ex) {
			// Fail fast: without a writable temp directory every PDF upload would fail at the
			// first request instead, which is a much worse way to find out.
			throw new IllegalStateException("could not create the upload temp directory", ex);
		}
	}

	private void restrictPermissions(Path file) {
		restrictPermissions(file, OWNER_ONLY_FILE);
	}

	/**
	 * Narrows permissions where the filesystem supports it.
	 *
	 * <p>Skipped silently on Windows, which has no POSIX permission view. Production is Linux
	 * (docs/plan.md section 11); a developer laptop holding a synthetic sample under its own
	 * user account is not the threat this guards against.
	 */
	private void restrictPermissions(Path file, Set<PosixFilePermission> permissions) {
		try {
			Files.setPosixFilePermissions(file, permissions);
		}
		catch (UnsupportedOperationException | IOException ex) {
			log.debug("Filesystem does not support POSIX permissions; skipping");
		}
	}
}
