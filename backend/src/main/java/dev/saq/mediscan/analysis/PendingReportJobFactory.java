package dev.saq.mediscan.analysis;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.upload.TempFileStore;

/**
 * A job that does nothing but clean up after itself.
 *
 * <p>The placeholder the upload layer is built against before the pipeline exists
 * ({@code LLD} step 3 of the build order). A report submitted through it stays {@code PENDING}
 * for ever, which is the honest outcome: nothing has processed it.
 *
 * <p>It still deletes the temp file. A placeholder that leaked uploaded files would break
 * {@code CLAUDE.md} rule 2 for as long as it existed, and "it's temporary" is not a reason to
 * leave an unmasked medical report on disk.
 *
 * <p><strong>Delete this class when {@code ReportJobRunner} lands.</strong> It is the only
 * {@link ReportJobFactory} until then, and two implementations would fail to start.
 */
@Component
public class PendingReportJobFactory implements ReportJobFactory {

	private static final Logger log = LoggerFactory.getLogger(PendingReportJobFactory.class);

	private final TempFileStore tempFiles;

	public PendingReportJobFactory(TempFileStore tempFiles) {
		this.tempFiles = tempFiles;
	}

	@Override
	public Runnable create(UUID reportId, JobSource source) {
		return () -> {
			try {
				log.warn("No pipeline is wired; report id={} stays PENDING", reportId);
			}
			finally {
				tempFiles.deleteQuietly(source);
			}
		};
	}
}
