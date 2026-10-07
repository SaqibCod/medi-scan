package dev.saq.mediscan.upload;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import dev.saq.mediscan.analysis.ReportJobFactory;
import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.llm.DailyCapGuard;
import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.SourceType;
import dev.saq.mediscan.session.Session;
import dev.saq.mediscan.session.SessionRepository;
import dev.saq.mediscan.stats.StatsRecorder;

/**
 * Turns an accepted request into a queued report.
 *
 * <p>The order of operations is the whole design here, and it is chosen so that a rejected
 * request leaves nothing behind - no temp file, no report row, no queue slot
 * ({@code LLD} 6.1):
 *
 * <ol>
 * <li>validate, which is pure and cheap;</li>
 * <li>check the daily LLM cap, so a caller is told {@code 429 CAPACITY} now rather than given
 * a {@code 202} for a report that cannot finish;</li>
 * <li>check the queue has room, before anything is written;</li>
 * <li>write the temp file, which is the first thing that has to be cleaned up;</li>
 * <li>insert the report row;</li>
 * <li>submit the job, undoing steps 4 and 5 if the pool refuses it.</li>
 * </ol>
 *
 * <p>Not {@code @Transactional}. The method writes a temp file and talks to an executor, and
 * holding a database transaction open across either is how a connection pool gets exhausted
 * under load. The single row insert is atomic on its own, and the compensating delete in the
 * rejection path is explicit for exactly that reason.
 */
@Service
public class CreateReportService {

	private static final Logger log = LoggerFactory.getLogger(CreateReportService.class);

	/** {@code Retry-After} for a full queue: long enough to drain, short enough to retry. */
	private static final long BUSY_RETRY_SECONDS = 30;

	private final UploadValidator validator;
	private final TempFileStore tempFiles;
	private final JobSubmitter jobs;
	private final DailyCapGuard capGuard;
	private final ReportRepository reports;
	private final SessionRepository sessions;
	private final ReportJobFactory jobFactory;
	private final StatsRecorder stats;
	private final Clock clock;
	private final long maxBytes;

	public CreateReportService(UploadValidator validator, TempFileStore tempFiles,
			JobSubmitter jobs, DailyCapGuard capGuard, ReportRepository reports,
			SessionRepository sessions, ReportJobFactory jobFactory, StatsRecorder stats,
			Clock clock, MediScanProperties properties) {

		this.validator = validator;
		this.tempFiles = tempFiles;
		this.jobs = jobs;
		this.capGuard = capGuard;
		this.reports = reports;
		this.sessions = sessions;
		this.jobFactory = jobFactory;
		this.stats = stats;
		this.clock = clock;
		this.maxBytes = properties.upload().maxBytes();
	}

	/** A file upload (contract section 4.1, option A). */
	public ReportEntity createFromFile(UUID sessionId, MultipartFile file, String consent) {
		validator.requireConsent(consent);
		FileType declared = validator.validateDeclaredFile(file);

		rejectIfCapReached();
		rejectIfQueueFull();

		Path tempFile = null;
		try (InputStream raw = file.getInputStream();
				BufferedInputStream in = new BufferedInputStream(raw, FileType.SIGNATURE_LENGTH * 2)) {

			// Read the signature, then put it back, so the file is written whole. Doing this
			// before writing means a file whose bytes contradict its type never lands on disk
			// at all (LLD 6.2).
			in.mark(FileType.SIGNATURE_LENGTH);
			byte[] header = in.readNBytes(FileType.SIGNATURE_LENGTH);
			in.reset();

			validator.validateSignature(header, declared);

			tempFile = tempFiles.write(in, maxBytes);
			return insertAndSubmit(sessionId, new JobSource.PdfFile(tempFile));
		}
		catch (IOException ex) {
			tempFiles.deleteQuietly(tempFile);
			// No message: it can name the upload. The client gets a generic 500 from the
			// advice, which is correct - this is a server-side read failure.
			log.warn("Upload could not be read: {}", ex.getClass().getSimpleName());
			throw new IllegalStateException("could not read the upload", ex);
		}
		catch (RuntimeException ex) {
			tempFiles.deleteQuietly(tempFile);
			throw ex;
		}
	}

	/** Pasted text or a sample (contract section 4.1, options B and C). */
	public ReportEntity createFromJson(UUID sessionId, CreateReportRequest request) {
		UploadValidator.ValidatedJsonRequest validated = validator.validateJson(request);

		rejectIfCapReached();
		rejectIfQueueFull();

		return insertAndSubmit(sessionId, validated.source());
	}

	private ReportEntity insertAndSubmit(UUID sessionId, JobSource source) {
		ReportEntity report = insertReport(sessionId, source.sourceType());

		if (!jobs.submit(jobFactory.create(report.getId(), source))) {
			// Lost a race for the last queue slot. Undo both the row and the file, so the
			// caller's retry starts from nothing rather than from a PENDING report that will
			// never be picked up.
			reports.deleteById(report.getId());
			tempFiles.deleteQuietly(source);
			throw busy();
		}

		stats.recordReportCreated();
		log.info("report accepted id={} source={}", report.getId(), source.sourceType());
		return report;
	}

	private ReportEntity insertReport(UUID sessionId, SourceType sourceType) {
		// The report expires with the session that owns it, so the expiry is copied rather
		// than recomputed - otherwise a report could outlive its owner and be unreachable but
		// undeleted (LLD 4).
		Session session = sessions.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.SESSION_INVALID,
						"Your session has expired. Start a new one."));

		Instant now = Instant.now(clock);
		ReportEntity report = new ReportEntity(UUID.randomUUID(), sessionId, sourceType, now,
				session.getExpiresAt());

		return reports.save(report);
	}

	private void rejectIfCapReached() {
		if (capGuard.isCapReached()) {
			throw new ApiException(ErrorCode.CAPACITY,
					"The demo has reached today's limit. Please try again tomorrow.",
					secondsUntilUtcMidnight());
		}
	}

	private void rejectIfQueueFull() {
		if (!jobs.hasCapacity()) {
			throw busy();
		}
	}

	private static ApiException busy() {
		return new ApiException(ErrorCode.BUSY,
				"The server is busy. Please try again in a moment.", BUSY_RETRY_SECONDS);
	}

	/** The cap resets at 00:00 UTC, so that is when retrying can succeed. */
	private long secondsUntilUtcMidnight() {
		Instant now = Instant.now(clock);
		Instant midnight = LocalDate.ofInstant(now, ZoneOffset.UTC)
				.plusDays(1)
				.atStartOfDay(ZoneOffset.UTC)
				.toInstant();
		return Math.max(Duration.between(now, midnight).toSeconds(), 1);
	}
}
